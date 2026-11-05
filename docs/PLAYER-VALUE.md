# Player Value

Player Value is a personal item vault and prestige score. It is not currency.

## Player-facing behavior

- `/value` opens a paginated personal vault viewer.
- Clicking a stored item starts a guarded withdrawal.
- `/value deposit` currently deposits the eligible stack held in the main hand. The current GUI is a viewer, not yet an insert-items chest interface; converting it to the requested deposit chest is still pending.
- The aggregate score is the sum of configured per-item values multiplied by deposited amounts.
- Only materials explicitly listed in `player-value.allowed-items` are accepted. Values can represent rarity tiers by assigning higher scores to rarer materials.
- The current defaults are DIAMOND=10, EMERALD=6, NETHERITE_INGOT=100, ANCIENT_DEBRIS=25, GOLD_INGOT=2. These are initial balancing values, not a final rarity economy.
- Deposited items remain in the vault until withdrawn. A withdrawal removes the corresponding score.
- Player Value cannot be transferred, traded, purchased, or used as payment.

## Safety implementation

Deposits use a durable PREPARED journal and plugin-owned PDC operation marker before inventory removal. The stored item and aggregate score are committed transactionally. Withdrawals use durable claims and PDC markers; uncertain claims are quarantined rather than automatically delivered again. Inventory operations are locked while a deposit or recovery is unresolved.

The plugin currently has a read-only vault GUI, main-hand deposit command, withdrawal workflow, and conservative recovery logic. These are implemented foundations, not a claim of exactly-once behavior under every possible crash. Full crash-injection integration tests against SQLite and PostgreSQL, staging validation, and backup/restore drills remain release gates. Keep `player-value.enabled: false` on the live server until those gates pass.

## PlaceholderAPI

- `%salchichonhardcore_value%`: full integer.
- `%salchichonhardcore_value_compact%`: compact display (for example `1.2k`, `3.5m`).

Example TAB `groups.yml` entry:

```yaml
_DEFAULT_:
  tabprefix: "&7[%d%] "
```

Merge any desired prefix into the existing TAB group configuration; do not replace the server's current setup.

## Administrative score corrections

Administrative changes made with `/hardcore stat <player> value <new_value> <reason>` are recorded in the dedicated `player_value_adjustments` ledger in the same transaction as the aggregate update and general stat event. These corrections are separate from item-backed vault deposits and withdrawals; they do not create, destroy, or transfer vault items.

## Operations

- PostgreSQL remains disabled by default.
- Keep the original SQLite database and backups.
- Never manually edit vault rows to resolve an uncertain inventory operation.
- Follow `PLAYER-VALUE-VAULT-RECOVERY.md` for evidence-based recovery.
