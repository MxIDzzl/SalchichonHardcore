# Staging test checklist

This build is for a maintenance-window staging test only. It is not a production release.

## Before replacing the plugin

1. Stop the Paper server.
2. Back up the complete server directory, especially `plugins/SalchichonHardcore/`, the existing `hardcore.db`, and the current plugin JAR.
3. Keep the backup outside the server's active plugin directory.
4. Install the CI artifact built from `feature/postgresql-foundation`; do not use a JAR from an older workflow run.
5. Keep `database.postgresql.enabled: false` and `player-value.enabled: false`. Do not configure PostgreSQL for this test.
6. Start the server and inspect the full startup log before allowing players in.

## What to test

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

- Player Value vault is not implemented; its configuration must stay disabled.
- Trade is not implemented.
- PostgreSQL importer and backend are not production-ready. The API v1 document is a contract only, not a running service.
- The API contract does not authorize exposing PostgreSQL or plugin credentials to the public website.
- This build does not include the separate website or admin panel.

## Rollback

If startup or any test fails:
1. Stop the server immediately.
2. Remove the test JAR.
3. Restore the previous plugin JAR and the complete plugin data backup together.
4. Start the server and confirm the previous version works.
5. Preserve the failed logs for diagnosis.

Never restore only the JAR if the test build has already changed the SQLite database schema.
