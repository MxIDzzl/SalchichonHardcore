# Item-for-item trade escrow

## Non-negotiable rules

- Trades exchange Minecraft items only. Player Value, currency, score, vault entries, and administrative stats are never part of a trade.
- Both participants must be online, distinct, and explicitly accept the same current offer revision.
- A change to either offer increments the revision and clears both confirmations.
- Every escrow payload is serialized server-side and stored with a SHA-256 fingerprint. Never accept item payloads from a client or web request.
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
4. Remove the marked stack, save player data, then mark the escrow row STORED in a transaction.
5. If recovery finds the marker still present, safely cancel that PREPARED row. If the marker is absent and the durable inventory state proves removal, promote it to STORED. Contradictory evidence is quarantined.
6. Settlement inserts recipient-bound delivery claims for every escrow item in one transaction and marks the trade LOCKED before any delivery.
7. Deliver each item with a unique PDC claim marker, save inventory, acknowledge the claim, and only then strip the marker.
8. On restart, exact marker + exact payload fingerprint can acknowledge an interrupted delivery. Missing, partial, or mismatched evidence remains RECOVERY_REQUIRED; never create a second copy automatically.

## Database requirements

- At most one active trade per player across OPEN, LOCKED, and RECOVERY_REQUIRED.
- Unique escrow operation and delivery receipt identifiers make retries idempotent.
- Row locks serialize offer changes, confirmations, lock, and settlement.
- A trade cannot reach COMMITTED until every escrow item has a durable delivery acknowledgement.
- Retain terminal trade and receipt records for audit; do not delete escrow evidence as a cleanup shortcut.

## Current implementation status

The immutable revision-bound session state model, confirmation invalidation rules, commit gate, recovery state, and SHA-256-verified escrow payload value object are implemented with unit tests. SQLite and PostgreSQL now have the same durable trade lifecycle primitives: participant locks, PREPARED escrow journal entries, revision invalidation, current-revision confirmations, atomic settlement lock, per-item delivery transitions, recovery quarantine, and commit only after every item is DELIVERED. Participant locks are released only in the same transaction that commits the trade. The live inventory PDC adapter, GUI/commands, cancellation/expiry return flow, staff reconciliation, delivery receipt UI, and crash-injection integration tests remain to be implemented. This document is the required safety contract for those steps.
