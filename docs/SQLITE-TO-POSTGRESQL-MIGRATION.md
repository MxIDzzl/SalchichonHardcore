# SQLite → PostgreSQL migration runbook

## Non-negotiable safety rules

- Never delete, rename, or overwrite `plugins/SalchichonHardcore/hardcore.db` during migration.
- Stop the Minecraft server before taking the final SQLite backup.
- Keep at least two independent copies of the original SQLite file.
- Import into a new PostgreSQL database/schema first. Do not point production at it until validation passes.
- The current SQLite schema contains `player_data(uuid, username, last_death_date, totem_uses)` and `admin_audit(id, target_uuid, target_name, admin_name, action, reason, created_at)`.
- Totem counts are historical only from when that feature was installed; do not fabricate earlier uses.

## Host-only network layout

- Run PostgreSQL on the same host/private container network as the Minecraft server and backend.
- Bind PostgreSQL to `127.0.0.1` or a private Docker/Pterodactyl network address.
- Do not publish port 5432 to the public Internet.
- Store DB credentials in host environment variables/secret manager. Never commit passwords or place production secrets in the plugin JAR.
- Use a dedicated database user with access only to the Salchichón database.
- Restrict backups and database files to the host administrator.

## Migration procedure

1. Schedule maintenance and stop the Minecraft server.
2. Copy `hardcore.db` to a timestamped, read-only backup location. Make a second copy off the active data volume.
3. Run SQLite integrity check (`PRAGMA integrity_check;`) against the copy. Require result `ok`.
4. Create a fresh PostgreSQL database. Enable `database.postgresql.enabled: true` only on the maintenance instance; plugin startup applies the versioned schema automatically.
5. From the server console, run `hardcore migrate-sqlite CONFIRM`. The command refuses to run if players are online or PostgreSQL is not the active backend.
6. The importer converts UUIDs, dates, counters and legacy local timestamps using `settings.timezone`, preserves audit IDs, and resets the identity sequence.
7. It verifies source integrity and destination row counts inside one PostgreSQL transaction; any failure rolls back the import.
8. Review the console report and perform the integration tests against PostgreSQL: join, death, daily reset, restore, audit insert, totem increment and placeholder reads.
9. Keep SQLite backups untouched for rollback. Never run both DBs as independent writable sources.

## Rollback

Stop the server, revert the host configuration to the preserved SQLite database and the last known-good plugin build, then restart. Do not attempt a reverse migration by deleting PostgreSQL data. Investigate and reconcile any writes made after cutover before retrying.

## Completion criteria

- SQLite integrity check passes.
- Every source row has a corresponding PostgreSQL row.
- UUID, username, death date, totem count, official deaths, playtime, Player Value aggregate, skin texture hashes, stat-event history, and audit action/reason/actor/timestamp match.
- No production cutover occurs until plugin code actually uses the PostgreSQL repository and the integration suite passes.

## Importer implementation status

The code-level importer is `SqliteToPostgresImporter`. It is intentionally not called by plugin startup and does not change the active database. It requires the versioned PostgreSQL schema to have been initialized on a fresh destination first.

The importer transfers and verifies row counts for:

- `player_data`: UUID, username, nullable death date and totem count.
- `player_stats`: official deaths, playtime ticks and Player Value aggregate.
- `player_skins`: validated public texture hashes from SkinsRestorer.
- `stat_events`: append-only official-stat history, preserving IDs and converting timestamps with the configured legacy timezone.
- `admin_audit`: original audit ID, target UUID/name, administrator, action, reason and timestamp.

It refuses the entire import if any unsupported operational records exist in `player_value_items`, `vault_operations`, `vault_claims`, `vault_cooldowns`, `player_value_adjustments`, `trade_sessions`, `trade_escrow_items`, `trade_item_operations`, `trade_player_locks` or `operation_receipts`. This prevents a partial cutover from silently discarding serialized item payloads, recovery journals or legacy trade history. Those records require a dedicated migration and manual reconciliation before any cutover.

The importer verifies SQLite integrity and destination row counts inside one PostgreSQL transaction. The source SQLite file is opened read-only. Any malformed UUID, negative statistic, invalid skin hash, unparseable timestamp, unsupported operational data, or non-empty destination causes a full rollback. It never deletes or modifies the SQLite source.

The importer is exposed through the console-only command `hardcore migrate-sqlite CONFIRM`. It is not run at startup. Run it only during maintenance, with a verified backup, no online players, a fresh PostgreSQL destination, and the plugin configured to use PostgreSQL. A successful import is not by itself permission to reopen the server; complete the integration tests and inspect every reported row count first.
