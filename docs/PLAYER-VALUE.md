# Player Value

Player Value is a prestige score backed by items held in a personal vault. It is not currency.

## Rules

- Value cannot be transferred, traded, purchased, or used as payment.
- Only materials explicitly listed under `player-value.allowed-items` are eligible.
- The configured number is the score credited per item; the stack score is unit value multiplied by amount.
- Deposited items remain stored until withdrawn. A withdrawal removes the corresponding score.
- The score is stored as a signed 64-bit integer in `player_stats.player_value`.
- TAB integrations can use PlaceholderAPI:
  - `%salchichonhardcore_value%`: full integer.
  - `%salchichonhardcore_value_compact%`: compact display (for example `1.2k`, `3.5m`).

Example TAB `groups.yml` entry:

```yaml
_DEFAULT_:
  tabprefix: "&7[&d%salchichonhardcore_value_compact%&7] "
```

This is an example only; merge the prefix into the existing group configuration instead of replacing the server's current TAB setup.

## Current implementation boundary

The SQLite and PostgreSQL adapters now expose persistent vault-item reads, deposits, score reads, cooldown storage, and transactional withdrawal-claim creation. Withdrawal claims have explicit states and are not automatically replayed after an uncertain delivery.

The player-facing inventory workflow is intentionally not enabled yet. In particular, do not set `player-value.enabled: true` until inventory serialization, PDC claim markers, recovery handling, GUI interaction, and crash-boundary tests are implemented and verified. The current database contract is groundwork, not a production-ready item-delivery guarantee.

## Operational guidance

- Keep PostgreSQL credentials in environment variables, never in this repository or the plugin JAR.
- Back up `hardcore.db` before importing legacy data.
- Run SQLite-to-PostgreSQL migration only during maintenance with no players online.
- Do not delete the original SQLite database after import until the imported data has been independently checked and backed up.

## Strict deposit policy foundation

The plugin now has a standalone `VaultItemPolicy` that accepts only materials explicitly present in the configured allow-list, rejects air, invalid/oversized stacks, metadata-bearing items, and score multiplication overflow. This is a validation primitive, not an enabled player feature: no Bukkit inventory is removed or delivered by this policy, and `player-value.enabled` remains `false` until the journaled inventory workflow and crash-boundary tests are complete.


## Administrative score corrections

Administrative changes made with `/hardcore stat <player> value <new_value> <reason>` are recorded in the dedicated `player_value_adjustments` ledger in the same transaction as the aggregate update and the general stat event. Each record stores the previous value, new value, signed delta, administrator, reason, and timestamp. These corrections are separate from item-backed vault deposits and withdrawals; they do not create, destroy, or transfer vault items. The ledger makes the difference explicit for public rankings and future administration rather than pretending every point came from a deposited item.


The persistence layer also enforces at most one active or quarantined vault operation per player with a partial unique index in SQLite and PostgreSQL schema v4. This protects against concurrent requests at the database boundary; the future Bukkit service must still provide player-facing locking and recovery.
