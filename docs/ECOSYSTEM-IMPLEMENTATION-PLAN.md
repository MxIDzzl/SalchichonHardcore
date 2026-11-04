# Salchichon SMP ecosystem — implementation contract

This document is the acceptance contract for the complete ecosystem. It intentionally distinguishes implemented foundations from features that are not yet implemented. A feature is not complete merely because its database tables or configuration exist.

## 1. Non-negotiable deployment and security rules

- Never commit credentials, license tokens, API keys, signing keys, or production database exports.
- PostgreSQL must listen only on localhost or a private host network. The web application must never connect directly to PostgreSQL.
- The Paper plugin talks to the backend over HTTPS. The backend owns database access and license validation; plugin secrets are injected by the host environment.
- License checks must use short-lived signed responses, server-side revocation, expiry, instance identity, replay protection, and key rotation. A modified JAR can bypass client checks; licensing is authorization and abuse control, not unbreakable DRM.
- Keep SQLite intact until a tested export, reconciliation report, backup restore drill, and rollback procedure have passed against a copy of the exact production database.
- No feature may silently replace, reset, or infer official player statistics from client-side/vanilla statistics.
- Admin changes to official values require actor, reason, previous value, new value, timestamp, and an immutable audit event.

## 2. Official player statistics

Authoritative fields:
- official_deaths: non-negative BIGINT, incremented once for each accepted player death event.
- playtime_ticks: non-negative BIGINT, accumulated from actual online session durations using monotonic elapsed time; flush periodically and on quit/disable.
- player_value: non-negative BIGINT prestige score; not currency and never transferable.
- totem_uses remains a separate lifetime counter.

Acceptance:
- Online and offline player profiles read the same persisted values.
- Death handling and stat event insertion are one transaction.
- Admin set/add/subtract operations require permission, target UUID, a mandatory reason, bounds validation, and one transaction for value + audit event.
- Admin correction updates the same source consumed by TAB, API, and web.
- Reconnect, server restart, duplicate event delivery, and scheduler shutdown must not double-count playtime or deaths.
- Vanilla statistics may be shown separately, never silently presented as official values.

## 3. Player Value vault

- Configure an explicit material allowlist and per-item score; deny unknown items by default.
- Reject shulker boxes, bundles, containers, custom NBT/meta, renamed/lored items, damaged items, and nested item payloads unless an item is explicitly and safely supported.
- Deposit removes exactly the validated quantity and credits value in one durable transaction; withdrawal debits first and delivers items with recovery-safe pending claims.
- Never permit negative balances, duplicate claims, value transfer, value purchases, or trade inclusion.
- Paginated inventory GUI; pages are generated on demand with no arbitrary page cap.
- Cooldown is configurable and persisted across restart.
- Every operation has an idempotency key and audit event; crash recovery must reconcile pending deliveries.

Recovery protocol and crash-boundary requirements are specified in [PLAYER-VALUE-VAULT-RECOVERY.md](PLAYER-VALUE-VAULT-RECOVERY.md). This is a design gate only; it does not mean the vault service or GUI exists.

## 4. Object-for-object trade

- Both players must be online and in the same supported world/session.
- GUI offer contains only supported item stacks; no Player Value, commands, permissions, currency, or external economy.
- Both sides independently confirm the exact offer revision.
- Any edit, withdrawal, disconnect, timeout, death, inventory change, or server restart invalidates confirmations.
- Finalization locks both inventories, revalidates capacity and exact item fingerprints, then commits atomically.
- If atomic inventory delivery cannot be guaranteed across a crash, use a durable escrow/claim model. Never attempt a best-effort swap that can duplicate or destroy items.
- Full audit trail and rate limits.

## 5. Backend API and license service

Suggested stack: Spring Boot, PostgreSQL, Flyway, HTTPS reverse proxy.
- Plugin authentication: per-instance credential provisioned outside the JAR; rotate and revoke server-side.
- Separate read-only public endpoints from authenticated ingestion and admin endpoints.
- Enforce schema validation, request size limits, rate limits, UUID ownership, permission scopes, and idempotency keys.
- Admin panel uses human authentication, MFA where available, role-based access, CSRF protection, secure cookies, and audit logging.
- Never expose arbitrary SQL or generic database mutation endpoints.
- Public endpoints must not reveal IPs, secrets, private admin notes, or hidden player data.

## 6. Public website

Suggested stack: Next.js with server-side API calls; never connect from browser to PostgreSQL.
- Overview: current season, difficulty and description, player totals, deaths, playtime, totems, value leaderboard, recent announcements and changelog.
- Player profile: stable UUID route, current username, SkinRestorer-resolved skin, official deaths, playtime, totems, Player Value, status/health only if the server explicitly publishes it.
- Skin lookup must fail closed to a neutral placeholder; never fabricate random skins.
- Announcements support published-at, scheduled-at, visibility, title, body, and optional image.
- “Next update” content is explicitly marked as teaser/leak and can be unpublished immediately.
- Accessible responsive layout, dark palette, pink/purple/blue accents without neon, reduced-motion support, SEO metadata, cache invalidation after admin edits.

## 7. Private admin panel

- Role-based access; read-only viewer, moderator, administrator.
- Player lookup by UUID/name; correction of official stats with mandatory reason and before/after preview.
- Season/difficulty management; changelog, announcements, teasers, and publication scheduling.
- Audit search and export; no deletion of audit events through normal UI.
- Every write is server-authorized, validated, rate-limited, and audited.

## 8. Release gates

A feature is releasable only after:
1. Unit tests for validation and edge cases.
2. Integration tests against the exact supported Paper API and PostgreSQL major version.
3. Restart/crash/reconnect tests for stateful workflows.
4. Security tests for permissions, forged requests, replay, and malformed payloads.
5. Backup and restore rehearsal.
6. Staging server soak test with representative player load.
7. Documented rollback and operator runbook.
8. CI green on the exact release commit.

## 9. Current state (2026-11-03)

Implemented on `feature/postgresql-foundation`:
- Versioned PostgreSQL schema v1-v4 with player data, audit, official-stat, stat-event, vault-item, trade-escrow, recoverable vault-operation, pending-claim, cooldown, and manual Player Value adjustment-ledger tables.
- HikariCP pool and PostgreSQL persistence adapter.
- SQLite persistence adapter remains available and is still the default; it has matching vault recovery tables.
- Read-only SQLite importer with transactional target writes and count reconciliation.
- Migration runbook and Java 25 Maven CI.
- Shared player persistence interface.
- Official deaths persisted transactionally with stat events; periodic monotonic playtime accumulation with flush on quit/shutdown.
- Audited admin correction command for deaths, playtime ticks, and Player Value (`/hardcore stat <jugador> <deaths|playtime|value> <valor> <motivo>`).
- `/hardcore check <jugador>` now displays official deaths, persisted playtime, and Player Value alongside life status.
- Shared tick-based playtime formatter with unit tests; Maven CI runs on each branch commit.
- Backend API v1 contract and public website/admin frontend contract documented; these are specifications, not deployed services.
- SQLite playtime updates are transactional; SQLite and PostgreSQL schemas include vault/trade persistence foundations.

Not implemented/release-ready yet:
- Player Value vault operations, scoring GUI, idempotent deposit/withdrawal recovery (schema/configuration foundations only; no live vault actions).
- Safe item escrow trade operations, inventory GUI, and crash-safe delivery reconciliation (database schema exists only).
- Backend API, license service, web app, and admin panel.
- End-to-end migration rehearsal, integration tests, and production cutover.

Do not enable PostgreSQL or advertise any not-implemented feature as available until its release gates pass.


### Player Value deposit journal update — 2026-11-03

- SQLite and PostgreSQL now expose a two-phase deposit persistence contract (`PREPARED` then transactional `COMMITTED`).
- Startup quarantines abandoned `DEPOSIT/PREPARED` operations as `RECOVERY_REQUIRED`, preventing blind replay or cancellation.
- An unverified cancellation API was intentionally removed; no code may cancel an operation until inventory evidence is available.
- Still outstanding: Bukkit inventory adapter, PDC marker/fingerprint, cooldown in the same transaction, inventory-aware recovery/reconciliation, and crash-boundary integration tests. Player Value remains disabled.


- 2026-11-04: la confirmación de depósitos ahora rechaza desbordamientos del agregado Player Value `BIGINT` en SQLite y PostgreSQL; la transacción completa se revierte si no puede actualizar exactamente una fila.
