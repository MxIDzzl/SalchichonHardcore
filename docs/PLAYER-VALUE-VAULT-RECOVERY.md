# Player Value Vault: recovery and transaction design

Status: design gate for the vault implementation. This document does not enable the feature or authorize a production cutover.

## Invariants

1. Player Value is prestige only. It is never a currency and cannot be transferred through trades.
2. A stored item is credited exactly once. A withdrawal removes the stored record and decreases its credited value exactly once.
3. Inventory mutations run on the Bukkit main thread. Database state transitions use short SQL transactions and unique operation IDs.
4. A player with an unresolved vault operation cannot use the vault again until recovery has completed.
5. The feature remains disabled by default until both database adapters and crash-recovery tests pass.
6. Items are deny-by-default: only configured materials with positive values are accepted. Items with custom metadata, enchantments, damage, containers, shulker contents, bundles, or unsupported data are rejected.

## Deposit protocol

A deposit must not be implemented as a simple "remove item, then insert row" sequence: a process crash between those steps can destroy the item. Nor may it insert the row first and remove the item later, because that creates free value.

Use a durable operation journal:

1. On the main thread, validate the selected inventory slot, material, amount, metadata, configured value, and cooldown. Capture the exact pre-operation slot state and a stable fingerprint.
2. Create a PREPARED DEPOSIT operation with a random operation UUID, player UUID, slot, amount, serialized item payload, expected pre-state fingerprint, and expected post-state fingerprint. Enforce one unresolved operation per player.
3. Put a temporary operation marker in the selected stack's PersistentDataContainer, then remove the requested amount from that slot. Do not allow inventory movement, clicks, drops, swaps, or disconnect while this synchronous operation is unresolved.
4. Finalize in one database transaction: insert the vault item, increment player_stats.player_value, mark the operation COMMITTED, and persist the cooldown.
5. Remove any temporary marker and release the player lock only after the database commit succeeds.
6. On failure before finalization, restore the exact pre-operation inventory state and cancel the journal entry. If restoration cannot be proven, retain RECOVERY_REQUIRED and prevent vault use rather than guessing.

Recovery rules:
- Marker still present: the stack was not fully removed; remove the marker, restore the original state if necessary, and cancel the prepared operation.
- Marker absent and the exact post-state is present: finish the database commit idempotently.
- Any other state: RECOVERY_REQUIRED and administrator review. Never infer success from an approximate material/amount match.

## Withdrawal protocol

A withdrawal must not delete the vault row and then blindly replay item delivery after restart.

1. In one transaction, lock the vault item row, verify ownership, create a PENDING claim with the serialized payload and unique claim UUID, and mark the vault item as reserved for that claim. Do not yet decrement the player's value.
2. On the main thread, deliver an item carrying a temporary claim UUID in its PersistentDataContainer. Prevent inventory interaction, drops, swaps, and disconnect until the item is durably acknowledged.
3. In one transaction, mark the claim DELIVERED, delete the reserved vault item, decrement player_stats.player_value by its credited value, mark the operation complete, and persist cooldown.
4. Strip the temporary claim marker only after the database acknowledgement. If the process crashes before acknowledgement, recovery scans the player's inventory for that exact claim marker and completes the acknowledgement without delivering a second copy.
5. If the claim marker is absent and the operation cannot be proven delivered, retain RECOVERY_REQUIRED; do not automatically issue another item.

The claim marker must be a plugin-owned PDC key and must never be copied from user-supplied item data. Marked stacks must not be mergeable with ordinary stacks before acknowledgement.

## Concurrency and persistence

- Use unique operation and claim UUIDs; SQLite and PostgreSQL now enforce a partial unique index allowing only one PREPARED, DELIVERY_PENDING, or RECOVERY_REQUIRED operation per player. A schema-v4 migration deliberately fails if legacy data already contains multiple active operations; reconcile those rows instead of silently discarding one.
- Use row locks for vault-item reservation and player-value updates.
- Use compare-and-set state transitions (expected current status) so retries cannot apply a transition twice.
- Update item rows, aggregate Player Value, operation status, and cooldown in the same SQL transaction.
- Keep SQLite and PostgreSQL behavior equivalent. SQLite must use explicit transactions and foreign-key enforcement.
- Never block the Bukkit main thread on an unbounded database wait. Database failures leave a recoverable journal state and a clear player-facing error.

## Required tests before enabling

- Deposit and withdrawal happy paths in SQLite and PostgreSQL.
- Duplicate request / repeated click / concurrent request idempotency.
- Crash simulation at every boundary: after PREPARED, after marker insertion, after inventory mutation, after database commit, after item delivery, and before marker removal.
- Player disconnect, server shutdown, and plugin disable during each operation.
- Full inventory, partial stack, empty slot, changed slot, invalid material, custom metadata, and cooldown persistence.
- Reconciliation tests proving that no path creates free Player Value or duplicates/losses an item silently.

## Current status

The schema and configuration groundwork exist. The actual vault service, inventory GUI, operation journal adapters, recovery listener, and the tests above are not yet implemented. `player-value.enabled` must remain `false`.

## Startup quarantine implemented

The shared persistence contract now includes `flagUncertainVaultClaims()`. On plugin startup, immediately after schema initialization and before player-facing listeners are registered:

1. Claims left in `DELIVERING` are changed to `RECOVERY_REQUIRED`.
2. Only the matching withdrawal operations are changed from `DELIVERY_PENDING` to `RECOVERY_REQUIRED`.
3. The number of quarantined claims is logged.
4. If the recovery sweep fails, plugin startup is aborted rather than allowing uncertain claims to be processed.

The sweep is transactional in both SQLite and PostgreSQL. It does not issue inventory items, infer whether an item reached a player's inventory, or automatically replay a claim. Manual reconciliation and a PDC-backed inventory evidence workflow are still required before withdrawals can be enabled.

## Atomic withdrawal finalization

The SQLite and PostgreSQL adapters finalize the claim row and its corresponding operation row in one database transaction. A claim cannot be reported as delivered while its operation remains `DELIVERY_PENDING`; any mismatch rolls back both state changes. This is database-state atomicity only: it does not make Bukkit inventory delivery exactly-once, which still requires the inventory marker/evidence protocol and manual reconciliation for ambiguous crash windows.


## Deposit persistence ledger (implemented groundwork)

Both SQLite and PostgreSQL now insert a `vault_operations` row with status
`COMMITTED` in the same SQL transaction as the vault item and aggregate
Player Value update. The operation UUID is the vault item UUID, so a repeated
insert cannot silently credit the same item twice: the primary-key conflict
rolls back the whole transaction.

This is a post-inventory finalization primitive only. It is **not** the
PREPARED-before-inventory-mutation protocol described above. No caller may
remove an item and then rely on this method as crash-safe delivery. The
inventory marker, PREPARED journal transition, atomic cooldown update,
per-player unresolved-operation lock, and reconciliation service remain
unimplemented. Keep `player-value.enabled: false`.

## Official death counter retry protection

Both persistence adapters now treat a second registration for the same player and in-game date as an idempotent retry: it returns success without incrementing the official death counter or appending another stat event. The player row is locked within the transaction in PostgreSQL; SQLite serializes access through its synchronized connection methods. An administrator's life restoration clears `last_death_date`, so a subsequent real death can still be counted. This protects repeated processing of the same daily death state; it is not a globally unique event ID across different dates.


## Administrative Player Value adjustments

Manual corrections to the aggregate score are intentionally separate from item deposits and withdrawals. Both database adapters write a `player_value_adjustments` ledger row atomically with the `player_stats.player_value` update and `stat_events` audit event. The signed delta records the administrative offset; it is not represented as a vault item and cannot be withdrawn or traded. PostgreSQL schema v4 creates this ledger for existing installations, while SQLite creates it idempotently during normal table initialization.


The database-level active-operation guard is now present in both adapters. It is a second line of defense: service-level checks and player interaction locks are still required, and a unique-index conflict must be reported as an existing/in-progress operation rather than retried as a new deposit or withdrawal.


## Two-phase deposit journal (database layer)

Deposits now expose three persistence operations in both SQLite and PostgreSQL:

1. `prepareVaultDeposit` writes a durable `DEPOSIT/PREPARED` journal row before any inventory mutation. The operation UUID is also the item UUID, making retries address the same journal record rather than creating a second credit.
2. `commitVaultDeposit` locks/reads that prepared record, inserts the vault item, increments the aggregate Player Value, and changes the journal to `COMMITTED` in one database transaction. Repeating a commit for an already committed operation is idempotent.
3. No cancellation operation is exposed. A prepared deposit cannot be cancelled until an inventory-aware proof can establish that the original item is still present.

At startup, `flagUncertainVaultOperations` also moves every remaining `DEPOSIT/PREPARED` row to `RECOVERY_REQUIRED`. This is deliberately conservative: the current database layer cannot prove whether Bukkit inventory mutation happened before the process stopped. Quarantined rows must not be automatically committed or cancelled. They keep the player's active-operation lock and require an inventory-aware reconciliation procedure.

**Safety boundary:** the Bukkit inventory adapter, persistent item marker/fingerprint, automated reconciliation, and crash-boundary integration tests are not implemented yet. Therefore this journal is a persistence primitive, not a complete crash-safe deposit flow. Keep Player Value disabled until those pieces and staging tests are complete.


### Validación adicional de límites numéricos — 2026-11-04

La confirmación transaccional del depósito ahora impide que el agregado `player_stats.player_value` exceda el máximo de `BIGINT` (`9223372036854775807`). La actualización condiciona el `UPSERT` al espacio restante y exige exactamente una fila afectada; si el límite se rebasaría, se revierte la transacción completa, incluyendo el alta del objeto y el cambio de estado de la operación. Esta protección está aplicada tanto en SQLite como en PostgreSQL. No sustituye la futura reconciliación del inventario ni habilita depósitos en vivo.


## Separación entre entregas normales y recuperación manual — 2026-11-04

La consulta `getPendingVaultClaims` devuelve exclusivamente reclamaciones `PENDING`. Las filas `RECOVERY_REQUIRED` quedan fuera del flujo normal y no deben entregarse automáticamente: requieren conciliación manual con evidencia del inventario. Esto evita que una futura rutina de inicio de sesión interprete una operación ambigua como una entrega segura.

Además, `markVaultClaimDelivering` solo permite pasar de `PENDING` a `DELIVERING` cuando existe la operación correspondiente del mismo jugador y sigue en `DELIVERY_PENDING`. La condición se valida en la propia sentencia SQL en SQLite y PostgreSQL. No resuelve la ventana de duplicación entre Bukkit y la base de datos; únicamente impide iniciar entregas desde reclamaciones u operaciones que ya no están en un estado coherente.


## Serialización de retiros concurrentes — 2026-11-04

PostgreSQL bloquea ahora primero la fila estable de `player_data` del propietario con `SELECT ... FOR UPDATE` antes de consultar o actualizar `vault_cooldowns`. Esto cubre el caso de un jugador que todavía no tiene fila de cooldown: bloquear únicamente `vault_cooldowns` no bloquea ninguna fila inexistente y permitiría que dos primeras solicitudes compitieran. La fila del jugador serializa esos retiros; después se valida el cooldown y se crea la reclamación dentro de la misma transacción. SQLite ya serializa esta ruta mediante su conexión compartida y método sincronizado.

Esto evita la carrera de cooldown en la capa de persistencia, pero no habilita la función: la entrega física del objeto continúa bloqueada hasta implementar PDC, verificación del inventario y reconciliación tras reinicios.
