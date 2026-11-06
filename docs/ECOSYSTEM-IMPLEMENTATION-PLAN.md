# Salchichon SMP ecosystem — implementation contract

This document is the acceptance contract for the complete ecosystem. It intentionally distinguishes implemented foundations from features that are not yet implemented. A feature is not complete merely because its database tables or configuration exist.

## 1. Non-negotiable deployment and security rules

- Never commit credentials, license tokens, API keys, signing keys, or production database exports.
- MySQL must listen only on localhost or a private host network. The web application must never connect directly to MySQL.
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

Suggested stack: Spring Boot, MySQL, Flyway, HTTPS reverse proxy.
- Plugin authentication: per-instance credential provisioned outside the JAR; rotate and revoke server-side.
- Separate read-only public endpoints from authenticated ingestion and admin endpoints.
- Enforce schema validation, request size limits, rate limits, UUID ownership, permission scopes, and idempotency keys.
- Admin panel uses human authentication, MFA where available, role-based access, CSRF protection, secure cookies, and audit logging.
- Never expose arbitrary SQL or generic database mutation endpoints.
- Public endpoints must not reveal IPs, secrets, private admin notes, or hidden player data.

## 6. Public website

Suggested stack: Next.js with server-side API calls; never connect from browser to MySQL.
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
2. Integration tests against the exact supported Paper API and MySQL major version.
3. Restart/crash/reconnect tests for stateful workflows.
4. Security tests for permissions, forged requests, replay, and malformed payloads.
5. Backup and restore rehearsal.
6. Staging server soak test with representative player load.
7. Documented rollback and operator runbook.
8. CI green on the exact release commit.

## 9. Current state (2026-11-05)

The authoritative product scope is documented in [PRODUCT-SCOPE.md](PRODUCT-SCOPE.md). Important scope corrections:
- Player Value is the personal item vault/rarity-weighted prestige score. The current UI is a read-only viewer with main-hand deposits and withdrawals; the requested deposit chest interface remains to be completed.
- Trades are handled by the external trade plugin already installed on the server. SalchichonHardcore no longer registers its own /trade runtime command. Legacy trade implementation/schema files remain in the feature branch and are not part of the release scope.
- zMClans remains the authoritative clan system. The website/API still need a safe synchronization/read model for clan membership and clan pages.
- SkinRestorer integration is implemented as an optional plugin bridge (API major 15) that stores the texture hash on player join. Website rendering and end-to-end verification remain.
- Advanced mob levels and special enemy variants are explicitly deferred to a later phase. Do not block the current portal/plugin scope on them.

### Paper plugin
- Daily Hardcore lifecycle, official death tracking, playtime, totems, PlaceholderAPI, SQLite default, MySQL adapter/schema/importer.
- Player Value persistence, inventory viewer, main-hand deposit, withdrawal claims, PDC markers and conservative recovery are implemented foundations.
- Internal trade command registration has been removed; use the server's external trade plugin.
- SkinRestorer texture-hash synchronization is implemented as an optional integration.
- Remaining release gates: complete chest-style deposit GUI, crash-injection tests for deposits/withdrawals, SQLite/MySQL integration and staging tests, exact Paper compatibility, backup/restore rehearsal.

### API and web
- Independent Spring Boot API and Next.js portal remain separate deployable services.
- Public overview, player directory/profiles, rankings, announcements and scheduled next update are implemented.
- The web project has responsive styling and profile routes. Clan information/pages and full SkinRestorer skin rendering still need end-to-end integration.
- Admin API is disabled by default; secure human authentication and the functional admin panel remain pending.
- The website must be visually polished, not reduced to a plain statistics page.

### Production safety
- Keep `database.mysql.enabled: false` on the live server until migration and restore drills pass.
- Keep Player Value disabled until chest UI, crash tests and staging pass.
- Preserve SQLite and verified backups.
- Do not merge draft PRs to main without explicit release approval and all relevant gates passing.
