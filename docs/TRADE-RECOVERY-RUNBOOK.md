# Trade recovery runbook (staff / staging only)

This procedure is deliberately conservative. A trade marked `RECOVERY_REQUIRED` is quarantined because the server cannot prove whether an item is still in an owner's inventory, was already delivered, or was only partially saved. Never resolve it by guessing.

## Scope and prerequisites

- Applies to item-only trades stored in `trade_sessions` and `trade_item_operations` (schema v6).
- Player Value, vault items, currency, totems, and life counters are outside trade recovery.
- The admin API endpoint `GET /api/v1/admin/trades/recovery` is an inspection aid only. It never returns serialized item payloads and cannot force delivery or cancellation.
- The existing `/hardcore trade-status <player>` command is read-only.
- `/hardcore trade-evidence <player>` scans online participant inventories (storage, armor, offhand, ender chest) and item entities in currently loaded chunks. It is read-only and reports marker/fingerprint matches or discrepancies.
- Do not perform this procedure on the live production world until the complete staging checklist and backup/restore rehearsal have passed.

## Immediate containment

1. Tell players to stop interacting with the affected trade. Do not ask them to drop, move, split, merge, or re-offer any item that may carry a trade marker.
2. Disable new trades in configuration (`trade.enabled: false`). This blocks new invitations/offers; it does not resolve or erase existing operations.
3. If the affected trade remains quarantined, schedule a maintenance window and stop Paper cleanly before inspecting offline player data.
4. Preserve the full server directory, the plugin data directory, the database, the exact plugin JAR, and logs. For PostgreSQL, take a consistent database backup; for SQLite, copy the database only after the server has stopped.
5. Work only on a restored copy in an isolated staging environment. Never experiment on the only copy of the live world or database.

## Read-only inspection

1. Query `GET /api/v1/admin/trades/recovery?limit=100&offset=0` through the private admin gateway, if configured.
2. Record trade ID, session state/revision, both participants, operation ID, owner, recipient, status, fingerprint, and timestamps.
3. Cross-check with `/hardcore trade-status <player>` and `/hardcore trade-evidence <player>` when safe to bring the isolated staging copy online.
4. Treat the evidence command as a partial live-world view only: it cannot inspect offline playerdata, unloaded chunks, backups, or historical inventory states. A dropped item has no reliable owner attribution from its marker alone.
4. The fingerprint is a SHA-256 digest of the serialized server-side item payload. It is evidence for comparison, not a way to reconstruct an item.
5. Do not query or export `item_payload` through the admin API. The API intentionally never selects or serializes that column.

## Evidence required before any resolution

For every operation, staff must establish all of the following using the isolated copy:

- Which exact player inventory contains the operation's escrow or delivery PDC marker.
- Whether the marked ItemStack matches the durable payload fingerprint after removing only the expected operation marker.
- Whether the durable operation state is consistent with the inventory save and any delivery/return acknowledgement.
- Whether there is exactly one physical copy across both participants' inventories and any persisted player data.
- Whether all items in the trade can reach a consistent terminal state without replaying an uncertain delivery.

A missing marker is not proof that an item was removed or delivered. A marker with a mismatched fingerprint is not proof of ownership. A database status alone is never sufficient evidence.

## Current limitation / prohibited actions

The current staff tools expose durable database state and fingerprints, plus a read-only in-game scan of online inventories and loaded dropped items. They do not provide a complete offline/chunk-unloaded evidence view or an audited reconciliation command. Therefore, if the evidence above cannot be established unambiguously, leave the session quarantined as `RECOVERY_REQUIRED`, preserve the backups, and escalate for code-level investigation.

Never:
- edit trade rows or statuses manually in SQL;
- delete an operation, escrow row, receipt, participant lock, or marker to "unstick" a trade;
- mark an operation `DELIVERED` or `RETURNED` based only on player recollection;
- resend an item when a delivery may already have occurred;
- clear PDC markers before the database acknowledgement is proven;
- re-enable trades on the affected world while unresolved operations remain.

## Release gate

Trade recovery is not production-ready until:
- a complete server-side evidence workflow can inspect online inventories, offline playerdata, loaded and unloaded chunk item entities, and exact item fingerprints without mutation;
- a staff reconciliation workflow has transactional, audited, idempotent transitions;
- crash-injection tests cover PREPARED, ESCROWED, DELIVERY_PENDING, DELIVERING, RETURN_PENDING, and acknowledgement boundaries on both SQLite and PostgreSQL;
- staging proves no duplication or loss across restart, disconnect, full inventory, and database failure scenarios;
- a full backup and restore rehearsal succeeds.

Until then, keep `trade.enabled: false` outside disposable staging tests.
