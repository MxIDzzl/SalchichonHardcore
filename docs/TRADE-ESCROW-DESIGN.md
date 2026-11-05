# Item-for-item trade escrow

## Non-negotiable rules

- Trades exchange Minecraft items only. Player Value, currency, score, vault entries, and administrative stats are never part of a trade.
- Both participants must be online, distinct, and explicitly accept the same current offer revision.
- A change to either offer increments the revision and clears both confirmations.
- Every escrow payload is serialized server-side, limited to 1 MiB, and stored with a SHA-256 fingerprint. Never accept item payloads from a client or web request.
- The plugin must never remove an inventory item until a durable PREPARED escrow journal and matching PDC operation marker exist.
- No item is delivered from escrow until the trade is durably LOCKED and both players' confirmations match the locked revision.
- All inventory mutation occurs on the Paper main thread. Database operations use short transactions off-thread; callbacks return to the main thread before touching Bukkit state.

## State machine

`OPEN -> LOCKED -> COMMITTED`

Exceptional paths:

- `OPEN -> CANCELLED`: no escrow item has been irreversibly committed; return every escrowed item to its original owner, with a durable delivery receipt.
- `OPEN -> EXPIRED`: same recovery requirements as cancellation.
- `LOCKED -> RECOVERY_REQUIRED`: any disconnect, restart, database failure, or inventory evidence mismatch during settlement. Never blindly retry a delivery.
- `RECOVERY_REQUIRED -> COMMITTED` only after both recipients' exact delivery markers and fingerprints are verified and acknowledged.
- `RECOVERY_REQUIRED -> CANCELLED` only after every escrowed item is proven returned to its original owner.

A LOCKED trade cannot be cancelled by a player. Staff recovery must reconcile both inventories and the durable escrow journal.

## Offer revisions and confirmation

- Each offer mutation is an atomic compare-and-set against the expected revision.
- Successful mutation increments revision and clears `confirmed_revision_a` and `confirmed_revision_b`.
- Confirmation is accepted only for the current revision and only from a participant.
- The transition to LOCKED is a single database transaction requiring both confirmation columns to equal the current revision.
- A stale GUI click or delayed callback is rejected if its revision differs from the durable row.

## Escrow inventory protocol

1. Validate the selected ItemStack and compute its serialized payload and fingerprint on the server.
2. Persist a PREPARED escrow operation with unique operation UUID before changing inventory.
3. Mark the exact stack with a plugin-owned PDC operation identifier and save player data.
4. Remove the marked stack, save player data, then mark the escrow row ESCROWED in a transaction.
5. If recovery finds the exact marker and matching fingerprint still present, remove the marked stack, save player data, and promote PREPARED to ESCROWED. If the marker is absent, the state is ambiguous and is quarantined; never infer removal from absence alone.
6. Settlement atomically marks the trade LOCKED and changes every ESCROWED item to DELIVERY_PENDING before any delivery.
7. Deliver each item with a unique PDC claim marker, save inventory, acknowledge the claim, and only then strip the marker.
8. On restart, exact marker + exact payload fingerprint can acknowledge an interrupted delivery. Missing, partial, or mismatched evidence remains RECOVERY_REQUIRED; never create a second copy automatically.
9. An owner may remove an ESCROWED offer only while the session is OPEN. The database increments the revision, clears both confirmations, and marks the item RETURN_PENDING before delivery back to the owner. The same PDC marker, saved-inventory acknowledgement, and RETURNED terminal state protect retries. Cancellation is allowed only after every item is RETURNED and every return marker is verified/cleared.

## Database requirements

- At most one active trade per player across OPEN, LOCKED, and RECOVERY_REQUIRED.
- Unique escrow operation and delivery receipt identifiers make retries idempotent.
- Row locks serialize offer changes, confirmations, lock, and settlement.
- A trade cannot reach COMMITTED until every escrow item is DELIVERED or RETURNED and terminal markers have been verified/cleared.
- Retain terminal trade and receipt records for audit; do not delete escrow evidence as a cleanup shortcut.

## Current implementation status

The immutable revision-bound session state model, confirmation invalidation rules, commit gate, recovery state, and SHA-256-verified escrow payload value object are implemented with unit tests. SQLite and PostgreSQL now have the same durable trade lifecycle primitives: participant locks, PREPARED escrow journal entries, revision invalidation, current-revision confirmations, atomic settlement lock, per-item delivery transitions, recovery quarantine, and commit only after every item is DELIVERED or RETURNED. Participant locks are released only in the same transaction that commits the trade. The player command and bilateral GUI are present: `/trade <player>`, `/trade accept|deny`, `/trade offer`, `/trade confirm`, `/trade status`, and `/trade cancel`. Offers are captured from the main hand, marked with a plugin PDC operation ID, and removed only after a PREPARED database journal exists. Clicking your own offered item in the GUI requests its return; the durable RETURN_PENDING journal and PDC marker allow safe retry after restart. An open trade can be cancelled once every offered item has been returned. Delivery uses a separate PDC claim marker, durable DELIVERING/DELIVERED transitions, saved inventory, and evidence-based recovery. GUI confirmations are bound to the exact displayed revision, so stale menus cannot confirm a changed offer. The feature is disabled by default until staging validation. Administrators can inspect active/recovery sessions with `/hardcore trade-status <player>`; it is read-only and shows the session/revision plus each escrow operation state and owner/recipient IDs. Idle-session expiry is implemented with durable EXPIRED state, RETURN_PENDING item recovery, and participant-lock release only after every escrow item reaches RETURNED. Remaining work: staff actions to reconcile RECOVERY_REQUIRED sessions and crash-injection integration tests against both SQLite and PostgreSQL. This document is the required safety contract for those steps.
