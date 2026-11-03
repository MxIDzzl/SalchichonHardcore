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
4. Create a fresh PostgreSQL database and apply `src/main/resources/db/postgresql/schema-v1.sql`.
5. Export every row from `player_data` and `admin_audit`; preserve UUIDs, names, nullable dates, totem counts, audit IDs, reasons and timestamps.
6. Convert UUID strings to native UUID, ISO dates to DATE, integer counters to BIGINT, and audit timestamps to TIMESTAMPTZ. Interpret existing local timestamp strings using the configured server timezone; do not blindly assume UTC.
7. Import within a transaction. Preserve audit IDs and reset identity sequences to the maximum imported ID.
8. Compare row counts and per-player checksums for all source/destination columns. Require exact match.
9. Run plugin integration tests against PostgreSQL, including join, death, daily reset, restore, audit insert, totem increment and placeholder reads.
10. Only after all checks pass, switch the host configuration to PostgreSQL and start the test server.
11. Keep SQLite backups untouched for rollback. Never run both DBs as independent writable sources.

## Rollback

Stop the server, revert the host configuration to the preserved SQLite database and the last known-good plugin build, then restart. Do not attempt a reverse migration by deleting PostgreSQL data. Investigate and reconcile any writes made after cutover before retrying.

## Completion criteria

- SQLite integrity check passes.
- Every source row has a corresponding PostgreSQL row.
- UUID, username, death date, totem count, audit action/reason/actor and timestamp match.
- No production cutover occurs until plugin code actually uses the PostgreSQL repository and the integration suite passes.
