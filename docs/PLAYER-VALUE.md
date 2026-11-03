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

## Current implementation boundary

The SQLite and PostgreSQL adapters now expose persistent vault-item reads, deposits, score reads, cooldown storage, and transactional withdrawal-claim creation. Withdrawal claims have explicit states and are not automatically replayed after an uncertain delivery.

The player-facing inventory workflow is intentionally not enabled yet. In particular, do not set `player-value.enabled: true` until inventory serialization, PDC claim markers, recovery handling, GUI interaction, and crash-boundary tests are implemented and verified. The current database contract is groundwork, not a production-ready item-delivery guarantee.

## Operational guidance

- Keep PostgreSQL credentials in environment variables, never in this repository or the plugin JAR.
- Back up `hardcore.db` before importing legacy data.
- Run SQLite-to-PostgreSQL migration only during maintenance with no players online.
- Do not delete the original SQLite database after import until the imported data has been independently checked and backed up.
