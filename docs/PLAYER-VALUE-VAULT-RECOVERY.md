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

- Use unique operation and claim UUIDs; enforce a partial unique constraint for one active operation per player (or equivalent transaction-level lock).
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
