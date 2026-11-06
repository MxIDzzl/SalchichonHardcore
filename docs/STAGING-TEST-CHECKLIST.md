# Staging test checklist

This build is for a maintenance-window staging test only. It is not a production release.

## Before replacing the plugin

1. Stop the Paper server.
2. Back up the complete server directory, especially `plugins/SalchichonHardcore/`, the existing `hardcore.db`, and the current plugin JAR.
3. Keep the backup outside the server's active plugin directory.
4. Download the `SalchichonHardcore-test-<SHA>` artifact produced only after all three database matrix jobs pass. Confirm the artifact SHA is the exact PR head being tested, then verify the JAR with the included `SHA256SUMS.txt`; never substitute a JAR from an older run.
5. Keep `database.mysql.enabled: false` and `player-value.enabled: false` for the initial SQLite compatibility smoke test.
6. Start the server and inspect the full startup log before allowing players in.

## What to test

### SQLite compatibility pass (safe first boot)

- Keep `database.mysql.enabled: false`, `database.mysql.import-sqlite-on-startup: false`, and `player-value.enabled: false`.
- Start with the backed-up existing plugin data directory; do not delete or recreate `hardcore.db`.
- Confirm the plugin reports a successful enable and that the existing player records remain present.
- Complete the smoke checks below before considering any database-backend change.

### MySQL staging pass (separate maintenance window)

- Use a dedicated, empty staging schema and credentials stored only in the host environment; never paste secrets into chat, GitHub issues, or logs.
- Enable MySQL only in the staging copy. Record the exact engine product/version printed by the plugin at startup.
- If importing, take a fresh SQLite backup first and enable the one-time importer for exactly one startup. Verify imported row counts and sample player/stat/audit records; confirm the source SQLite file remains unchanged.
- Restart once with the importer flag back to `false` and confirm MySQL is now the active backend.
- Do not enable Player Value during this pass.

- Plugin enables cleanly and connects to the existing SQLite database.
- Existing player life/death behavior still works.
- Totem uses increment and persist after reconnect/restart.
- Official deaths increment once per actual death.
- Playtime increases while online and is flushed on quit and clean shutdown.
- PlaceholderAPI values for deaths and playtime match the stored values.
- An operator can run `/hardcore stat <player> <deaths|playtime|value> <new_value> <reason>`; verify the mandatory reason and permission checks.
- `/hardcore check <player>` displays life status, last death, official deaths, registered playtime, and Player Value; compare every displayed value with the database and PlaceholderAPI.
- Restart the server and verify the values persist.
- Check logs for SQL errors, stack traces, scheduler errors, and unexpected warnings.

## Do not test / do not assume

- Player Value viewer, deposits, withdrawals, PDC markers, durable claims, and conservative recovery are implemented. Keep `player-value.enabled: false` until crash-injection tests, staging inventory validation, and backup/restore are complete.
- Trades are managed by the external trade plugin already installed on the server. SalchichonHardcore does not register native trade commands or perform trade delivery/recovery; use the external plugin's own documentation.
- MySQL schema/pool and the one-way SQLite importer are implemented, but MySQL remains disabled by default. A passing CI matrix is not provider connectivity evidence. Do not perform a production cutover until the actual Holy Host engine/version, fresh-database import, interruption/recovery rehearsal, and backup/restore have all passed.
- The read-only API and public web portal are separate services in `SalchichonSMP-Updates`; this plugin repository does not bundle or deploy them. No browser receives database credentials.
- The admin page is informational only; no real admin authentication or mutation endpoints exist yet.

## Rollback

If startup or any test fails:
1. Stop the server immediately.
2. Remove the test JAR.
3. Restore the previous plugin JAR and the complete plugin data backup together.
4. Start the server and confirm the previous version works.
5. Preserve the failed logs for diagnosis.

Never restore only the JAR if the test build has already changed the SQLite database schema.

