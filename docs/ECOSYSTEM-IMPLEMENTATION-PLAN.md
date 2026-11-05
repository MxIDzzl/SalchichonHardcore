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

## 9. Current state (2026-11-04)

Implemented in separate draft pull requests (all remain isolated from `main`):

### Paper plugin — `MxIDzzl/SalchichonHardcore`
- Versioned PostgreSQL schema v1-v4, HikariCP adapter, SQLite adapter/default, transactional legacy importer, and migration runbook.
- Official deaths/stat events, playtime tracking, audited administrative corrections, `/hardcore check`, and PlaceholderAPI statistics.
- Player Value persistence foundation: strict allow-list policy, prepared deposit journal, transactional commit with BIGINT overflow protection, withdrawal claims, atomic cooldown persistence, pending/recovery separation, and startup quarantine of ambiguous operations.
- PostgreSQL withdrawals lock the player's durable `player_data` row before checking cooldown, closing the first-withdrawal race when no cooldown row exists.
- CI uses Java 25 and the repository's pinned Paper API.

### Independent API and web — `MxIDzzl/SalchichonSMP-Updates`
- Spring Boot API service in `api/`, with read-only public overview/player/profile/ranking/announcement endpoints, bounded pagination, CORS allow-list, health/readiness, Dockerfile, and Flyway announcement schema.
- Runtime Hikari connections are read-only; migration credentials are separate and Flyway is disabled by default.
- BIGINT Player Value is serialized as a decimal string; sort expressions are allow-listed and covered by tests.
- Next.js public portal with season overview, live API statistics, player directory/profiles, rankings, announcement section, responsive styling, and explicit unavailable states rather than fabricated data.
- Web and API have independent CI workflows and can be built/deployed as separate processes. Their draft PRs have passed CI on their current branch heads as of this update.

Still not implemented/release-ready:
- Bukkit inventory adapter, PDC item markers/fingerprints, deposit/withdrawal GUI, and inventory-aware crash reconciliation for Player Value.
- `/trade` GUI, item escrow lifecycle, bilateral revision-bound confirmation, and crash-safe delivery.
- Admin authentication/authorization, MFA/session hardening, audit-backed mutation endpoints, and functional admin panel.
- SkinRestorer integration and published skin/profile visuals.
- Plugin license/instance enrollment, revocation, and authenticated API ingestion.
- End-to-end PostgreSQL migration rehearsal, staging integration/crash tests, deployment, and rollback rehearsal.

The public web/API foundations are code, not production deployments. Keep PostgreSQL and Player Value disabled in the live server, retain SQLite backups, and do not merge draft PRs to `main` until their feature-specific release gates pass.


### Player Value deposit journal update — 2026-11-03

- SQLite and PostgreSQL now expose a two-phase deposit persistence contract (`PREPARED` then transactional `COMMITTED`).
- Startup quarantines abandoned `DEPOSIT/PREPARED` operations as `RECOVERY_REQUIRED`, preventing blind replay or cancellation.
- An unverified cancellation API was intentionally removed; no code may cancel an operation until inventory evidence is available.
- Still outstanding: Bukkit inventory adapter, PDC marker/fingerprint, cooldown in the same transaction, inventory-aware recovery/reconciliation, and crash-boundary integration tests. Player Value remains disabled.


- 2026-11-04: la confirmación de depósitos ahora rechaza desbordamientos del agregado Player Value `BIGINT` en SQLite y PostgreSQL; la transacción completa se revierte si no puede actualizar exactamente una fila.

### Retiro de bóveda: aislamiento de reclamaciones inciertas — 2026-11-04

Las reclamaciones `RECOVERY_REQUIRED` ya no aparecen en `getPendingVaultClaims`, por lo que no pueden confundirse con trabajo seguro de entrega automática. La transición a `DELIVERING` exige además que la operación asociada pertenezca al mismo jugador y permanezca en `DELIVERY_PENDING`, validado en la propia sentencia SQL en ambos motores. La finalización sigue siendo transaccional. Esto reduce inconsistencias de estado, pero no ofrece entrega exactly-once de inventario: Player Value permanece desactivado hasta implementar marcadores PDC y reconciliación.
