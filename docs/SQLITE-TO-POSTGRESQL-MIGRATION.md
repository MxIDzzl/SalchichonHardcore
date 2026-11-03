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
- UUID, username, death date, totem count, audit action/reason/actor and timestamp match.
- No production cutover occurs until plugin code actually uses the PostgreSQL repository and the integration suite passes.

## Importer implementation status

The code-level importer is `SqliteToPostgresImporter`. It is intentionally not called by plugin startup and does not change the active database. It requires the versioned PostgreSQL schema to have been applied manually to a fresh destination first.

The importer currently transfers only the legacy tables that exist in SQLite:

- `player_data`: UUID, username, nullable death date and totem count.
- `admin_audit`: original audit ID, target UUID/name, administrator, action, reason and timestamp.

The newer `player_stats` and `stat_events` tables are not populated by the legacy importer because the old SQLite database does not contain equivalent authoritative records. They remain empty until the official-statistics feature is implemented; do not infer historical deaths, playtime or Player Value from unrelated data.

The importer verifies source integrity and destination row counts and uses one PostgreSQL transaction. It deliberately refuses to import into non-empty destination tables. The source SQLite file is opened read-only. Timestamp strings that cannot be parsed as the legacy `LocalDateTime` representation cause a full rollback and require manual review.

The importer is exposed through the console-only command `hardcore migrate-sqlite CONFIRM`. It is not run at startup. Run it only during maintenance, with a verified backup, no online players, a fresh PostgreSQL destination, and the plugin configured to use PostgreSQL. The command does not delete or modify the SQLite source. A successful import is not by itself permission to reopen the server; complete the integration tests and inspect the report first.
