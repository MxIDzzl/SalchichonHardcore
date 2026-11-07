# Public website and administration frontend contract

The web application is a separate deployment from the Paper plugin and API. It may
share one Next.js codebase for public pages and the private admin area, but it must
not be packaged into the plugin JAR and must never connect directly to PostgreSQL.

## Public information architecture

### Home

- Current season name, season day, start date, and difficulty.
- Server online/status indicator only when a trusted status source is available.
- Featured recent update and next scheduled public announcement.
- Shortcuts to player rankings and the full changelog.

### Rankings

Initial sortable rankings:
- Player Value (descending).
- Official deaths (descending).
- Playtime (descending).

Each row links to a public player profile. Pagination must use API cursors, not
client-side loading of the entire player table. A stable secondary sort by UUID
prevents players jumping between pages when values tie.

### Player profile

Display only fields explicitly returned by the public API:
- Current username and SkinRestorer-provided skin/texture.
- Player Value (compact display plus exact integer on detail/tooltip).
- Official deaths and recorded playtime.
- Totem uses.
- Lives/health only if the server policy publishes them.
- Public achievements or season information only when supported by authoritative
  data.

Do not expose UUID unless required for a stable public route. Prefer a public slug
or API-issued public identifier. Never show IP addresses, audit history, admin
notes, installation identifiers, or private account/session data.

### Updates

- Changelog entries with title, date, version/season, category, and body.
- Scheduled announcement cards only after their configured publication time.
- A leak/upcoming update may have a planned date and title, but draft/private
  announcements must never be exposed through public endpoints.
- Date/time display uses the server's configured timezone with an explicit timezone
  label; API timestamps remain UTC.

## Admin information architecture

All admin routes require server-verified sessions and role authorization. Hiding a
button in the browser is not access control.

- Dashboard: server/season summary, recent audit activity, and pending announcements.
- Player search/detail: inspect public stats and correct supported official values.
- Announcements: create drafts, edit, schedule, publish, unpublish, and archive.
- Season/server settings: edit season title/description/dates and allowed difficulty.
- Audit log: filter by actor, target, action, date, and request ID.

Every mutation requires a reason. Show the before/after values and confirmation
for high-impact changes. The UI must display the resulting audit record ID and
server response; do not optimistically report success before the API confirms it.

## Design system

Visual direction: elegant dark interface with restrained pink, purple, and blue
accents. Avoid neon glow, excessive gradients, and noisy animations.

- Dark neutral surfaces with clear text contrast.
- Pink is reserved for Player Value/highlight states; purple/blue for navigation,
  metadata, and secondary emphasis.
- Use consistent spacing, typography, focus states, and responsive breakpoints.
- Tables must remain usable on mobile; provide compact card layouts where necessary.
- Respect reduced-motion preferences and keyboard navigation.
- Loading, empty, stale, and error states are designed explicitly.
- Never rely on color alone to communicate status.

## Frontend/API boundary

- Public pages call only `/public/v1/*`.
- Admin pages call only `/admin/v1/*) through same-origin server-side routes where
  practical; session cookies are HttpOnly and never exposed to client JavaScript.
- Plugin endpoints and installation tokens are never called from a browser.
- Use server-side validation and API authorization for all writes.
- Cache public read endpoints with bounded freshness. Never cache admin responses
  or private player data in a shared CDN cache.
- Invalidate ranking/profile caches after successful authoritative writes or event
  ingestion. Include stale-data timestamps where freshness is not immediate.
- Escape announcement markdown/HTML and sanitize any rich text before rendering.
- All API errors include a request ID; show that ID in admin error details.

## Accessibility and quality gates

- WCAG AA contrast for text and interactive controls.
- Complete keyboard operation and visible focus.
- Semantic headings, landmarks, table headers, labels, and accessible dialogs.
- Automated tests for public/private route separation, role restrictions, CSRF,
  escaped announcement content, pagination, responsive navigation, and empty/error
  states.
- End-to-end tests must verify that a player stat correction updates the admin view,
  public profile/rankings, and audit log consistently.
- Production deploy is blocked until API contract tests and the exact server/API
  integration test suite pass.
