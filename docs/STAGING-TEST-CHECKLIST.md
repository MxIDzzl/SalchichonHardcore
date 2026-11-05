# Staging test checklist

This build is for a maintenance-window staging test only. It is not a production release.

## Before replacing the plugin

1. Stop the Paper server.
2. Back up the complete server directory, especially `plugins/SalchichonHardcore/`, the existing `hardcore.db`, and the current plugin JAR.
3. Keep the backup outside the server's active plugin directory.
4. Install the CI artifact built from `feature/postgresql-foundation`; do not use a JAR from an older workflow run.
5. Keep `database.postgresql.enabled: false`, `player-value.enabled: false`, and `trade.enabled: false` for the initial compatibility smoke test. Do not configure PostgreSQL for this test.
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

- Player Value viewer, deposits, withdrawals, PDC markers, durable claims, and conservative recovery are implemented. Keep `player-value.enabled: false` until crash-injection tests, staging inventory validation, and backup/restore are complete.
- The item-only trade workflow, bilateral GUI, revision-bound confirmations, escrow, returns, settlement, and expiry are implemented. Keep `trade.enabled: false` except on disposable staging accounts/worlds. Staff reconciliation for `RECOVERY_REQUIRED` and automated crash-injection coverage remain release gates. Follow [TRADE-RECOVERY-RUNBOOK.md](TRADE-RECOVERY-RUNBOOK.md); the current API endpoint and `/hardcore trade-status` are inspection-only and do not resolve uncertain inventory state.
- PostgreSQL schema/pool and the one-way legacy SQLite importer are implemented, but PostgreSQL remains disabled. Do not perform a production cutover until a fresh-database import and full integration/rollback rehearsal pass.
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

## Separate isolated trade test (only after the compatibility smoke test)

Use a disposable staging world and two test accounts. Do not reuse real player inventories.

1. Back up the staging database and both player inventories.
2. Set `trade.enabled: true` only on this isolated staging server.
3. Invite/accept, reject self-invites, verify invite expiry and one-active-trade-per-player.
4. Offer one item from each player. Verify the exact item disappears only after a PREPARED journal and PDC marker are saved.
5. Confirm from one player and verify the other is still pending. Change either offer and verify both confirmations are invalidated.
6. Open the GUI before an offer change, then click its stale confirm button. It must reject the stale revision.
7. Click your own offered item to return it. Verify the item returns once, the revision increments, and the other player's confirmation is cleared.
8. Confirm a non-empty final offer from both players. Verify the session locks once and each item is delivered exactly once.
9. Test full recipient inventory. Delivery must remain pending without partial duplication; free space and use `/trade status` to resume.
10. Cancel an empty session and a session where all items have been returned. Cancellation must be refused while any item is still escrowed or RETURN_PENDING.
11. Restart staging at controlled points around PREPARED escrow, inventory removal, DELIVERING, delivery acknowledgement, RETURN_PENDING, and return acknowledgement. A missing or contradictory marker must quarantine the trade rather than retry blindly.
12. Verify `/hardcore trade-status <player>` is read-only and reports each operation state. Check logs and database rows after every scenario.

Do not mark trade as release-ready until automated crash-injection tests cover the same boundaries against both SQLite and PostgreSQL, and a staff reconciliation procedure for `RECOVERY_REQUIRED` has been validated.
