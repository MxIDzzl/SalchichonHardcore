# Backend API contract (v1)

This document is a future design contract for the independent SalchichonHardcore API.
The current sibling repository already contains the public read API and a limited private
admin API, but plugin event ingestion, installation licensing, human admin sessions, and
the full admin site described here are not implemented. The Paper plugin, API, MySQL
instance, public site, and admin site are separate runtime components. SQLite remains
the plugin default until the MySQL staging and migration gates pass.

## Runtime boundaries

- Paper plugin: authoritative in-game event source and command adapter.
- API: validates plugin credentials, accepts idempotent event batches, serves public
  read models, and exposes authenticated administrative operations.
- MySQL: private network only; neither the plugin nor browser connects directly
  to it. The API owns database credentials.
- Public Next.js site: consumes only public read endpoints.
- Admin Next.js area: consumes admin endpoints and never receives plugin credentials.

The API must bind to a private interface by default. TLS is mandatory whenever traffic
leaves localhost/private trusted transport. CORS is an allow-list of the deployed web
origins; it is not authentication.

## Authentication and licensing

### Plugin identity

Each installation receives a revocable installation credential. Store only a
one-way hash of the credential in MySQL. The raw credential is injected into
the server process as an environment variable and is never placed in config.yml,
logs, command output, the JAR, or a web bundle.

Requests carry:
- `Authorization: Bearer <installation-token>`
- `X-Installation-Id: <stable-installation-uuid>`
- `X-Request-Id: <unique-request-uuid>`
- `Content-Type: application/json`

The API checks token hash, installation status, allowed product, and revocation
before processing any plugin endpoint. Rotation supports an overlap window:
issue a second credential, deploy it, verify successful traffic, then revoke the
old credential. Revocation must take effect on the next authenticated request;
short-lived cache entries must not extend the documented revocation bound.

Do not describe this as unbreakable DRM. A server owner can modify a locally
installed JAR. Licensing is a revocable service authorization and integrity signal.

### Admin identity

Admin browser sessions use secure, HttpOnly, SameSite cookies over HTTPS, CSRF
protection for state-changing requests, short session lifetimes, and server-side
role checks. Roles initially are `OWNER`, `ADMIN`, and `READ_ONLY`. Every write
requires a non-empty reason and records actor, target, before/after values, request
ID, timestamp, and outcome in an append-only audit record.

Plugin installation credentials must never authorize admin endpoints.

## API conventions

- Prefix: `/api/v1`.
- JSON timestamps: RFC 3339 UTC.
- UUIDs: canonical lowercase/hyphenated strings.
- Counts and Player Value: integer values; never floating point.
- Pagination: `limit` (default 25, max 100) and opaque `cursor`; stable ordering
  by the primary sort key plus UUID.
- Errors: `{ "code": "...", "message": "...", "requestId": "..." }`.
- Unknown fields are ignored only when safe; required fields and enum values are
  validated strictly.
- Mutations require an `Idempotency-Key` UUID. Reusing a key with a different
  canonical request body returns HTTP 409.

## Plugin endpoints

### `POST /plugin/v1/events/batch`

Accepts a bounded batch (maximum 100 events) of immutable events. Each event has
`eventId`, `playerUuid`, `occurredAt`, `type`, and a validated type-specific
payload. Initial event types: `OFFICIAL_DEATH`, `TOTEM_USED`, and
`PLAYTIME_DELTA`.

The unique event ID is the deduplication key. Replays return the original accepted
result and must not increment counters again. Reject a batch atomically if its
structure is invalid; do not partially apply an invalid batch. A valid batch may
return per-event accepted/already-processed outcomes.

### `GET /plugin/v1/installation/status`

Returns installation ID, product, active/revoked status, API compatibility range,
and server-side feature flags. It must not return secrets or database details.
The plugin fails closed for protected API operations when authorization is revoked,
but local hardcore survival behavior must have an explicit documented offline policy.

### `GET /plugin/v1/players/{uuid}/stats`

Returns official counters and the authoritative Player Value aggregate. It is a
read endpoint; it cannot modify a player's value.

## Public endpoints

- `GET /public/v1/server`: server status, current season, difficulty, season dates,
  and latest published update.
- `GET /public/v1/rankings?metric=player_value|deaths|playtime`: paginated ranking.
- `GET /public/v1/players/{uuid}`: public profile, username, skin texture metadata,
  official deaths, playtime, totems, lives/health when published, Player Value, and
  public profile visibility.
- `GET /public/v1/updates`: published changelog entries and scheduled announcements.
- `GET /public/v1/updates/{slug}`: one published update.

Public DTOs must be explicit allow-lists. Never serialize persistence entities or
admin-only columns directly. Private notes, audit reasons, tokens, IP addresses,
email addresses, and internal identifiers are not public profile fields.

Skin display must use the server's configured SkinRestorer profile/texture source.
Do not infer a skin from an offline-mode UUID or silently show a random Steve/Alex.

## Admin endpoints

- `GET /admin/v1/players` and `GET /admin/v1/players/{uuid}`
- `PATCH /admin/v1/players/{uuid}/stats`: correct deaths/playtime/value with reason.
- `GET /admin/v1/audit`: filterable, cursor-paginated audit log.
- `POST /admin/v1/announcements` and `PATCH /admin/v1/announcements/{id}`:
  create/edit draft announcements, leaks, and publication schedule.
- `PATCH /admin/v1/server/season`: season title, description, dates, difficulty.
- `GET /admin/v1/server/config` and `PATCH /admin/v1/server/config`: only an
  explicit allow-list of editable configuration values.

All admin mutations run in one database transaction with the corresponding audit
record. If audit persistence fails, the mutation rolls back. No endpoint accepts
arbitrary SQL, arbitrary table/column names, or unrestricted configuration paths.

Manual Player Value corrections are recorded in the adjustment ledger and must
remain distinct from item-backed vault value. They never create withdrawable items.

## Core MySQL model

The existing plugin schema is the initial persistence baseline. The API schema will
add, in separate versioned migrations:

- `installations`: installation UUID, token hash, status, product, created/revoked
  timestamps, credential version.
- `api_idempotency`: installation, key, request hash, response status/body, expiry.
- `ingested_events`: immutable unique event IDs, installation, player, type,
  payload, occurred/received timestamps.
- `admin_users`, `admin_sessions`, and `admin_audit`: role/session and append-only
  administration history.
- `seasons`, `announcements`, and publication state.
- public read models/materialized views for rankings and profile aggregates where
  needed; they are rebuilt from authoritative records, not edited by the browser.

Migrations are forward-only in production. Backups and restore drills precede schema
changes. The API must not run destructive schema synchronization at startup.

## Operational requirements

- Health endpoints: liveness without database details; readiness checks MySQL.
- Structured logs include request ID and installation ID, never bearer tokens.
- Rate limits apply by installation, IP, and admin identity.
- Bound request body size, batch size, pagination size, and database query time.
- Metrics include request latency, auth failures, duplicate event rate, DB pool
  saturation, and failed audit writes.
- Backups encrypted at rest, off-host, with a tested restore procedure.
- Secrets supplied through environment/secret manager; rotate and revoke credentials.
- Database account is least-privilege and private-network-only.

## Release gates

The API is not production-ready until automated tests cover token rotation/revocation,
role isolation, CSRF, CORS, request validation, idempotent replay, conflicting
idempotency keys, transaction rollback when audit insertion fails, rate limiting,
pagination stability, and restore from backup. Plugin/API compatibility must be
tested against the exact Paper server build before deployment.
