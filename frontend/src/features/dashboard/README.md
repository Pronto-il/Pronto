# features/dashboard

> **Pronto SOS professional frontend, MS2 (2026-08-21).** `ProDashboardLayout` gained a fifth tab,
> **`/pro/sos`**, and mounts `ProSosProvider` alongside the existing `PendingRequestsProvider` — at
> the same scope and for the same reason (the nav badge and the SOS screen share one poll and one
> `/user/queue/sos` subscription). The screen itself is **not** in this feature: it lives in
> `features/sos` with the rest of the SOS product semantics, and this feature only routes to it.
>
> Mounting the provider on the layout rather than on the route is deliberate — an SOS offer has a
> ~2-minute window, so a professional sitting on the availability calendar has to be told about it
> without navigating anywhere. The provider raises the toast and lights the badge from anywhere
> under `/pro/*`.
>
> `IncomingRequestsPage` (`/pro/requests`) is untouched and stays what it was: an accept/reject
> feed of *scheduled orders*. SOS was deliberately not folded into it — "אישור" would have meant
> two different things on one screen, and an SOS offer is a different resource with a countdown, a
> different vocabulary (available ≠ awarded) and a four-step operational flow once won.
>
> One knock-on fix: a fifth tab pushed the mobile tab strip past a 430px viewport, clipping
> "פרופיל". The SOS tab now renders a short label (`SOS`) below 640px and the descriptive one
> ("קריאות SOS") on the desktop sidebar.

## Purpose
The professional-facing dashboard.

## Responsibilities
- Availability management UI (backed by the `availability` backend package).
- Incoming requests view (Standard and SOS booking requests awaiting accept/reject).
- Job-status update actions (Confirmed -> On the Way -> Completed, plus Cancel).
- Routing to the professional's Pronto SOS surface (`features/sos`'s `ProSosPage`) and surfacing
  its live count badge.

## Professional weekly availability calendar — M3/M4 (2026-08-18)

Full design record: `docs/architecture/professional-weekly-calendar-design.md` §7/§10.
Backend (M1/M2) was already complete and live-verified before this pass started. This pass
is **frontend-only**, covers M3 (working-hours setup/edit) and M4 (read-only weekly grid),
and does **not** include M5's click interactions (block create/edit/delete, booked-block
navigation) — that's a separate, later dispatch.

`/pro/availability` now renders **`WeeklyAvailabilityPage.tsx`** (new) instead of the old
`AvailabilityPage.tsx` — same route, unchanged path (`frontend/src/app/router.tsx`). Composes,
top to bottom:
1. `SosAvailabilityToggle` — rendered verbatim, unchanged, same position as before. Not
   touched by this pass at all.
2. The working-hours setup/edit entry point (`WorkingHoursForm.tsx`, M3) — see below.
3. `WeeklyCalendarGrid.tsx` (M4) — the read-only weekly grid, see below.

`SlotForm.tsx`/`SlotList.tsx`/`AvailabilityPage.tsx` are **left in the repo, unreachable from
any route** — per the design's explicit "kept, not deleted yet, until M6 makes the underlying
`availability_slots` endpoints fully vestigial" instruction (§7.1/§10). They still compile
(nothing else changed about them) but are dead code from this point on.

### `WorkingHoursForm.tsx` (new) — M3

A 7-row form (one row per weekday, Sunday first, matching
`professional_working_hours.weekday`'s convention), each row: an enable/disable toggle
(a one-off `role="switch"` button, same pattern `SosAvailabilityToggle` already established —
no shared `Switch` primitive was added, still a single-purpose control) plus start/end
`type="time"` inputs (hidden when the row is disabled). Calls `PUT
/api/availability/working-hours` with the full week on save (a full replace, not a partial
patch). Client-side validates the same rules the backend enforces (each enabled day needs
both times, `endTime > startTime`) before submitting, with per-row field errors; a generic
`role="alert"` banner covers network/unexpected-`VALIDATION_ERROR` failures, matching
`SlotForm.tsx`'s existing convention.

Controlled component — takes `workingHours: WorkingHoursItem[]` (from `GET
/api/availability/working-hours`, 0-7 entries), `onSaved`, and an optional `onCancel` (edit
mode only). `WeeklyAvailabilityPage` owns the single `GET` call and decides which of the two
entry points to render:
- **First-time setup** (fewer than 7 entries returned): the form renders full-page, with a
  "דלג, אגדיר מאוחר יותר" (skip, configure later) ghost action — per the design's explicit
  "skippable, not a hard gate" framing (§7.2): skipping reveals the (all-muted,
  outside-working-hours) calendar without saving anything.
- **Later edit** (exactly 7 entries already configured), **as originally built in M3 —
  historical, superseded by MS12, see below**: a compact read-only summary (one line per
  weekday: hours or "לא עובד/ת") plus an "עריכת שעות עבודה" link that expanded the same form
  inline. **As of MS12 (2026-08-19)**, this is no longer the current behavior: the permanent
  summary was removed and the edit entry point now opens `WorkingHoursForm` in the shared
  `Modal` primitive instead of inline — see the MS12 section below for the current behavior.

**Deviation from the design doc (historical — resolved by MS12, see below)**: §7.2 said the
edit entry point should open the form "in a modal/drawer (reuse whatever new `Modal` primitive
M5 introduces)." That primitive didn't exist yet at the time of this M3/M4 pass — the design
itself assigned it to M5, and this pass's brief explicitly said not to build it speculatively
then. Until M5 landed, the edit entry point expanded `WorkingHoursForm` **inline** on the page
instead (the same "toggle an inline editor" pattern `SlotList.tsx` already uses for its own
row-level edit mode) — functionally equivalent at the time, and `WorkingHoursForm`'s own props
already fully supported either host. `Modal.tsx` was subsequently built (M5), and MS12
(2026-08-19, see below) completed the swap this paragraph anticipated — **this deviation no
longer exists in the current code.**

### `WeeklyCalendarGrid.tsx` (new) — M4, view-only

The Google-Calendar-like weekly view. Consumes `GET /api/availability/calendar?from=&to=` for
the currently-visible week (Sunday-Saturday), passing bare `"yyyy-MM-dd"` date strings (not
full ISO instants) so the backend — not the browser's own timezone — is the single source of
truth for where a calendar day begins (`AvailabilityService#parseCalendarInstant` accepts
either shape; a bare date is interpreted as midnight in the fixed `Asia/Jerusalem` business
timezone server-side).

- **Layout**: 7 day columns + a time axis, default visible range 06:00-23:00 (auto-extended if
  any real segment falls outside it), 30-minute gridlines, vertical scroll within a
  capped-height container beyond the visible range.
- **Visual states**: `AVAILABLE` (light success tint + "זמין" label), `BLOCKED` (diagonal-hatch
  fill + lock icon + "חסום" label + `reason` if present), `BOOKED` (renders the shared
  `StatusBadge` component directly inside the segment — reuses its existing `OrderStatus`
  color mapping rather than inventing new colors, satisfying the "not color-only" accessibility
  requirement for free since `StatusBadge` already carries a Hebrew text label).
  `orderStatus: 'COMPLETED'` bookings get an additional muted/reduced-opacity treatment
  (`.segmentCompleted`), per the design's "completed gets its own visual state" note. Time
  outside working hours has no segment data at all — the day column's own muted background
  (`--color-surface-secondary`) shows through, with no click affordance (there is no click
  behavior at all yet — that's M5).
- **Week navigation**: prev/next/"today" buttons; the visible week lives in the `?week=` URL
  search param (normalized to that week's Sunday) rather than component-local-only state, both
  so a reload/share preserves the visible week and to pre-wire M5's §43 requirement that a
  booked-block click-through's back navigation can return to `/pro/availability?week=...`.
- **Mobile (`max-width: 640px`, this codebase's existing breakpoint)**: switches to a
  single-day focused view with a horizontal day-switcher chip strip, **not** a shrunk
  7-column grid — two parallel DOM trees (desktop/mobile) sharing the same computed
  per-day segment data, toggled via CSS `display: none` at the breakpoint (no JS media-query
  listener needed).
- **Polling**: `usePolling` at a 25s interval (coarser than order-tracking's 3-5s, per the
  design's own §31 recommendation — this isn't a live-tracking screen).
- **Loading/error**: initial "טוען את היומן…" text, then a retryable `role="alert"` error
  banner (DESIGN_SYSTEM.md §61's exact copy pattern: message + "אפשר לנסות שוב בעוד רגע." +
  "נסה שוב" button) if the first fetch fails; a transient poll failure after data has already
  loaded is swallowed silently (same graceful-degradation behavior `OrderTrackingPage`'s own
  polling already has) rather than replacing already-rendered data with an error state.

**No click interactions this milestone** — `AVAILABLE`/`BLOCKED`/`BOOKED` segments render
informationally only, no `onClick`, no `CalendarBlockModal`, no booked-block navigation. That's
M5's scope entirely.

**Timezone assumption, flagged**: segment timestamps are bucketed into day columns and
formatted using the browser's own local timezone (`new Date(isoString)` + this codebase's
existing `formatDateTime.ts` helpers), not the fixed `Asia/Jerusalem` business timezone the
backend uses internally for derivation. This matches every other timestamp display already in
this app and is correct for a user physically in Israel (the v1.0 audience) — called out
explicitly in the component's own doc comment rather than left as a silent assumption.

### `shared/api/availability.ts` extension

`getWorkingHours`/`updateWorkingHours`/`getAvailabilityCalendar` plus their
`WorkingHoursItem`/`WorkingHoursItemRequest`/`WorkingHoursListResponse`/`SegmentType`/
`CalendarSegment`/`CalendarResponse` types — see `shared/api/README.md`'s own entry for detail.
Shapes verified directly against the real backend DTOs and live-verified against a running
instance (see below), not copied from the design doc's illustrative JSON.

## Professional weekly availability calendar — M5 (2026-08-18): block CRUD, booked-block navigation

Full design record: `docs/architecture/professional-weekly-calendar-design.md` §7.3/§7.4/§10
(M5). Resumes the M3/M4 pass above — `WeeklyCalendarGrid` is no longer view-only; every
segment is now clickable.

### `CalendarBlockModal.tsx` (new) + `.module.css`

Create/edit/delete a manual availability block, built on the (already-existing, reused
as-is — **not** rebuilt) `shared/components/Modal.tsx` primitive. Mode is decided by prop
presence, not a separate flag: passing `block` (an existing `{ id, startAt, endAt, reason }`,
sourced straight from a clicked `BLOCKED` segment — no extra `GET`) puts the modal in edit
mode; passing `initialRange` (`{ startAt, endAt }`, from a clicked `AVAILABLE` segment) puts
it in create mode. Two `datetime-local` inputs + an optional short `reason` text field, the
same input/validation shape `SlotForm.tsx` already established (`toDateTimeLocalValue`
helper reused verbatim as a local copy — no shared cross-file helper existed to import,
consistent with this being the closest existing precedent rather than a new pattern).

- **Create**: `POST /api/availability/blocks`. **Edit**: `PATCH
  /api/availability/blocks/{id}` (full replace of `startAt`/`endAt`/`reason`, matching the
  backend's own "resend the whole shape despite the PATCH verb" convention). **Delete**
  (edit mode only, a destructive full-width button in the form body): `DELETE
  /api/availability/blocks/{id}`, no confirmation step — same low-stakes,
  easily-recreated reasoning `SlotList.tsx`'s own slot-delete already uses.
- **Error handling**: `VALIDATION_ERROR` → generic field-error copy (same as `SlotForm`);
  the two new 409 codes (`BLOCK_OVERLAPS_EXISTING_BLOCK`/`BLOCK_OVERLAPS_BOOKING`) → specific
  Hebrew banner messages via a `BLOCK_ERROR_MESSAGES` map, the same
  known-error-code-map convention `ORDER_ACTION_ERROR_MESSAGES` (`features/booking`) already
  established; anything else → `GENERIC_ERROR_MESSAGE`. All three codes were live-verified
  against the real backend (see below).
- `onSaved` fires after a successful create/update/delete so the parent
  (`WeeklyCalendarGrid`) can refetch the calendar — the same "callback tells the parent to
  refresh" convention `SlotList.tsx`'s `onRefreshNeeded` already uses, not a shared cache/
  invalidation mechanism.
- `shared/api/availability.ts` gained `createAvailabilityBlock`/`updateAvailabilityBlock`/
  `deleteAvailabilityBlock` (`POST`/`PATCH`/`DELETE /api/availability/blocks*`) plus their
  `CreateBlockRequest`/`BlockResponse` types, verified directly against the real backend DTOs.
  `shared/api/httpClient.ts` gained a `patch()` method (previously only `get`/`post`/`put`/
  `delete` existed) — a small, narrow addition, needed because `PATCH
  /api/availability/blocks/{id}` is the first `PATCH` endpoint any frontend code in this
  codebase has called.

### `WeeklyCalendarGrid.tsx` — click-routing (M5)

Every segment (`SegmentBlock`) is now an interactive element (`role="button"`, keyboard
`Enter`/`Space` activation, `cursor: pointer`, `:focus-visible` outline) instead of a purely
informational `div`. `CalendarWeekView`'s `handleSegmentClick` branches on `segment.type`
**before** any modal/edit code is reachable — the critical constraint that a `BOOKED` click
must never open block-editing UI (design §15) is satisfied structurally, not as a late
filter:
- `AVAILABLE` → opens `CalendarBlockModal` in create mode, pre-filled from the clicked
  segment's own `startAt`/`endAt` (the professional can narrow the two `datetime-local`
  inputs before saving — a deliberate simplification over computing a click-position-derived
  sub-range from mouse Y coordinates, flagged as a judgment call within the design's own
  "your call" framing for this interaction).
- `BLOCKED` → opens the same modal in edit mode, pre-filled straight from that segment's
  `blockId`/`startAt`/`endAt`/`reason`.
- `BOOKED` → `navigate(\`/orders/${orderId}\`, { state: { returnTo: { weekStart } } })` —
  never touches `CalendarBlockModal` state at all in this branch, so a `BOOKED` click
  structurally cannot reach any block-editing code path.
- Outside working hours: no segment is ever rendered there (unchanged from M4), so there is
  no click affordance — nothing new needed for this case.

A successful block create/edit/delete calls the modal's `onSaved`, which closes the modal and
calls the existing `usePolling`-returned `refetch()` — an explicit refresh on top of the
already-existing ~25s polling interval (M4), not a replacement for it.

### `OrderTrackingPage.tsx` extension (M5) — see `features/booking/README.md`

Not this package's own component, but the click-through destination for a `BOOKED` segment
and the back-navigation partner for §43's week-context preservation — documented in
`features/booking/README.md`'s own M5 section rather than duplicated here.

### Verification performed for M5

No browser-automation tool was available in this environment (consistent with every prior
frontend milestone). Verification was: (1) `tsc -b` and `vite build` both clean, zero errors
in any new/changed file; (2) `oxlint` clean except three pre-existing warnings unrelated to
this pass (`ProfessionalList.tsx` fast-refresh warnings, a `qa-tmp-ms9` script's unused
variable); (3) a full manual code review against
`docs/architecture/professional-weekly-calendar-design.md` §7.3-§7.5/§10/§15/§35-36/§43; (4)
live API-contract-conformance testing against a real running backend + a fresh Postgres
instance (28 migrations applied cleanly) via `curl`: registered a professional + customer,
set a full working week, created a manual block (12:00-13:00 Israel time, Wednesday) and a
Standard order (booked 15:00-16:00 Israel time, 60-minute default duration) on the same day,
fetched `GET /api/availability/calendar` and confirmed the exact 5-segment layout the
design's §36 worked example describes; **edited the block via `PATCH
/api/availability/blocks/{id}`** (round-tripped a Hebrew `reason` string correctly) and
**deleted it via `DELETE`** (`204`); **triggered both new 409s** (`BLOCK_OVERLAPS_EXISTING_BLOCK`
by overlapping the still-existing block, `BLOCK_OVERLAPS_BOOKING` by overlapping the order's
booked range) and confirmed the exact codes `CalendarBlockModal`'s `BLOCK_ERROR_MESSAGES` map
expects; fetched `GET /api/bookings/orders/{orderId}` as the professional and confirmed `id`/
`bookedEnd`/`customerPhone`/`customerName`/`professionalName` are all present and correctly
shaped (`customerPhone` populated while the order was still `PENDING`, confirming design
§9.1's "assignment, not confirmation" rule) — exactly matching `OrderTrackingPage.tsx`'s new
rendering logic; fetched `GET /api/issues/{issueId}` and confirmed it matches
`IssueDetailResponse` exactly; **accepted the order and re-fetched the calendar**, confirming
the `BOOKED` segment's `orderStatus` changed from `PENDING` to `CONFIRMED` on the very next
`GET` with no extra code — the structural basis for §31's polling-driven concurrent-update
requirement (`WeeklyCalendarGrid`'s existing ~25s `usePolling` interval, unchanged by this
pass, will observe this same effect in a live browser). A Windows-native Postgres process was
again found shadowing port 5432 — worked around with a fresh throwaway `postgres:16`
container on port 5434 for this verification session only (removed afterward, no
`docker-compose.yml` change).

**Full interaction matrix, verified as above**: `AVAILABLE` → create (`201`, confirmed);
`BLOCKED` → edit (`200`, Hebrew `reason` round-tripped) / delete (`204`, confirmed); `BOOKED`
→ navigation only, never block-editing UI (verified by code review — `handleSegmentClick`'s
`BOOKED` branch returns immediately after `navigate(...)`, before `setBlockModal` is ever
reachable in that branch). §43's back-navigation round-trip was verified by code review only
(no browser tool): `WeeklyCalendarGrid` passes `state: { returnTo: { weekStart } }` using the
exact `toDateKey(weekStart)` value already used to build the `?week=` URL param elsewhere on
the same page, and `OrderTrackingPage` reads `location.state?.returnTo` and builds
`/pro/availability?week=${weekStart}` from it — the same string round-trips through both
sides with no reformatting in between.

### Verification performed for M3/M4

No browser-automation tool was available in this environment (consistent with every prior
frontend milestone in this project). Verification was: (1) `tsc -b && vite build` and `oxlint`
both clean, zero errors/warnings in any new/changed file; (2) a full manual code review against
`docs/architecture/professional-weekly-calendar-design.md` §7/§10 and `DESIGN_SYSTEM.md`;
(3) live API-contract-conformance testing against a real running backend + a fresh Postgres
instance (all 28 migrations applied cleanly) via `curl`: registered a professional + customer,
set a full working week, created a manual block (12:00-13:00, Monday) and a Standard order
(booked 15:00-16:00 — 60 minutes, the actual `DEFAULT_JOB_DURATION_MINUTES` value M2 built,
not the design doc's illustrative 90-minute example) on the same day, then fetched `GET
/api/availability/calendar` and confirmed the exact 5-segment layout the design's §36 worked
example describes (`AVAILABLE` / `BLOCKED` / `AVAILABLE` / `BOOKED` / `AVAILABLE`), with every
field (`type`/`startAt`/`endAt`/`blockId`/`reason`/`orderId`/`orderStatus`) matching this
file's `CalendarSegment` interface exactly. Also confirmed `PUT /api/availability/working-hours`
round-trips exactly as `WorkingHoursForm.tsx` expects, and that a deliberately-invalid payload
(an enabled day with no `startTime`/`endTime`) returns `400 VALIDATION_ERROR`, matching the
form's own catch-block handling. A Windows-native Postgres process was found shadowing port
5432 (the exact issue flagged in this task's brief) — the docker-compose `pronto-postgres`
container was silently unreachable from the host despite reporting healthy; worked around by
running an ad hoc `postgres:16` container on port 5434 for this verification session only
(removed afterward, `docker-compose.yml` itself untouched).

## Status
**Partially implemented, Frontend Milestone 3 (2026-08-16); post-QA bug-fix pass
(2026-08-17); SOS-availability toggle added Frontend Milestone 4 (2026-08-17)**:
incoming-request accept/reject, a read-only job history, availability-slot create/list, and
the SOS-availability toggle. Job-status progression (On the Way / Completed) was **not**
built as part of this pass — out of this milestone's scope (see the brief's explicit
exclusion list). **Superseded, Frontend Milestone 6 (2026-08-18)**: the on-the-way/complete
actions are now built, but they live on `features/booking/OrderTrackingPage.tsx`, not in
this package — see that bullet below and `features/booking/README.md`'s Frontend Milestone
6 section for full detail. `MyJobsPage` in this package remains intentionally read-only/
link-only. **Frontend Milestone 9 (2026-08-18)**: `SlotList` gained inline edit/delete for
not-yet-booked slots (fully QA-verified live, after two follow-up bug fixes), and
`IncomingRequestCard` gained a read-only issue-photos row (code complete and correct; was
**non-functional in a real browser at the time this round closed**, due to a pre-existing,
cross-cutting image-auth gap — **resolved separately by backend MS9, 2026-08-18**, now
renders correctly) — see that section below for full detail.

**Frontend Milestone 4 (2026-08-17):**
- `SosAvailabilityToggle` (new component, rendered at the top of `AvailabilityPage`, above
  the existing Standard-slot section) — a one-off accessible toggle button (`role="switch"`)
  for the professional's `sos_availability` flag (`GET`/`PUT
  /api/availability/sos-availability`), labeled "זמין/ה לעבודות דחופות (SOS) כרגע" per PRD
  §3.5.2's framing, with its own loading/error handling mirroring `SlotForm`'s
  submit-in-flight pattern. Lives on `AvailabilityPage` rather than a new dashboard tab —
  both are the `availability` domain (same `/api/availability/*` backend package as the
  Standard slot calendar), and `ProDashboardLayout`'s three tabs deliberately avoid
  dead/thin nav items, so a fourth tab for a single toggle would contradict that. No new
  `Switch` primitive was added to `shared/components` — this is a single-usage toggle, not a
  generic one.
- `IncomingRequestCard`'s doc comment updated: SOS orders are now a real, reachable case
  (previously "not produced by this frontend yet"), no functional change — the `sosTag` and
  `order.bookedEnd == null` handling were already correct.

- `ProDashboardLayout` (`/pro`, `PROFESSIONAL`-only) replaces the old `ProPlaceholderPage`
  with a simple tab shell (בקשות חדשות / העבודות שלי / יומן זמינות) wrapping an
  `<Outlet />` — deliberately not the full multi-item sidebar from DESIGN_SYSTEM.md §53
  (ביקורות, הגדרות etc. don't have screens yet).
- `IncomingRequestsPage` (index route under `/pro`) short-polls `GET
  /api/bookings/orders/me?status=PENDING` (`usePolling`, 5s interval — `overview.md` §3.3
  names the incoming-request feed as a short-polling consumer alongside the tracking
  screen) and, per pending order, makes a follow-up `GET /api/issues/{issueId}` call to
  resolve category/description for `IncomingRequestCard` — an accepted N+1 pattern at MVP
  scale (no batch endpoint exists), with per-issue caching so a later poll tick doesn't
  re-fetch issues already resolved. Accept/Reject wired to `acceptOrder`/`rejectOrder`,
  each triggering an immediate `refetch()` on success rather than waiting for the next poll
  tick. No location/distance field is shown on the card — no endpoint returns one for an
  order.
- `MyJobsPage` (`/pro/jobs`, added post-QA 2026-08-17) — a read-only list of the
  professional's own orders (`GET /api/bookings/orders/me`, no status filter, mirroring
  `features/booking/MyOrdersPage.tsx`'s customer-side pattern), each row showing date/
  time/price/`StatusBadge` and linking to `/orders/:id`. Fixes a real gap: once an order
  left the pending feed (accepted or rejected) there was no in-app way to see it again
  short of typing `/orders/{id}` directly. No accept/reject/on-the-way/complete actions
  rendered directly on this list — it stays link-only, matching `MyOrdersPage.tsx`'s
  pattern. **Note (Frontend Milestone 6, 2026-08-18)**: on-the-way/complete actions now
  exist for professionals, but they live on `features/booking/OrderTrackingPage.tsx`
  (reached via this list's `/orders/:id` links), not on `MyJobsPage` itself — this page's
  own doc comment was updated in the same pass to stop claiming those actions are
  unbuilt/out of scope, since that's no longer accurate; the page's own behavior did not
  change.
- `AvailabilityPage` (`/pro/availability`) combines `SlotForm` (two `datetime-local`
  inputs → `POST /api/availability/slots`) and `SlotList` (`GET /api/availability/slots/me`,
  showing each slot's `isAvailable` state). **As of Frontend Milestone 9 (2026-08-18)**,
  `SlotList` also supports inline edit/delete for not-yet-booked slots — see below.

**Post-QA fix (2026-08-17):** `IncomingRequestCard`'s loading spinner now tracks which
action is actually in flight — `IncomingRequestsPage` stores `{ orderId, action }` instead
of a bare processing id, and the card takes separate `isAccepting`/`isRejecting` props
(previously a single `isProcessing` boolean made the Accept button show a spinner even
while Reject was the one running; both buttons still disable together during any in-flight
action).

Not built here: the fuller professional dashboard sidebar (ביקורות/הגדרות — no backing
screens yet, apart from `פרופיל`, added Frontend Milestone 8, see below). Job-status action
buttons (on-the-way/complete) **are now built** (Frontend Milestone 6, 2026-08-18) but
belong to `features/booking/OrderTrackingPage.tsx`, not this package — see that package's
README.

## Frontend Milestone 9 — gap-fixes (2026-08-18)

Full design record: `docs/architecture/frontend-ms9-gap-fixes-design.md`. Branch
`frontend/MS9-gap-fixes`, local only — uncommitted, not pushed/merged.

**Real, verified status (not the same for all three items — read carefully):**
- Availability slot edit/delete (`SlotForm`/`SlotList`/`AvailabilityPage`): **fully
  implemented and fully QA-verified live**, including two follow-up bug fixes found and
  closed during QA (see below).
- `IncomingRequestCard`'s issue-photo thumbnail row: **code was complete and correct at the
  time this round closed, but the feature was then non-functional in a real browser**, due
  to a pre-existing, cross-cutting bug outside this round's scope. **Resolved separately,
  immediately after, by backend MS9 (2026-08-18)** — see the dedicated note below, now
  updated to reflect the fix.

- **`SlotForm.tsx`** — now reusable for both create and edit via an optional `slot` prop.
  In edit mode, pre-fills `startTime`/`endTime` from the given slot (new local
  `toDateTimeLocalValue` helper, the inverse of the existing ISO-string submit conversion),
  submits via the new `updateAvailabilitySlot(slot.id, payload)` instead of
  `createAvailabilitySlot`, shows a "ביטול" secondary button (`onCancel`, edit-mode only),
  and uses an "עדכון" submit label instead of "הוספת זמן פנוי". `onCreated` was renamed to
  `onSaved` (fires on either a successful create or update) — the one existing call site
  (`AvailabilityPage`) was updated in the same pass. Handles `SLOT_IN_USE` (409) as a
  distinct message, edit-mode only (unreachable in create mode — a slot can't be "in use"
  before it exists), and fires a new optional `onConflict` callback alongside it so the
  owning `SlotList` can react (see below) — this callback is a small addition beyond the
  design doc's literal `SlotFormProps` listing, needed to bridge the "trigger a re-fetch on
  `SLOT_IN_USE`" behavior the doc calls for in §1b without `SlotList` re-implementing
  `SlotForm`'s own error-catching; the doc explicitly leaves the refetch-trigger mechanism to
  implementation judgment ("pronto-coding may choose to trigger a re-fetch...").
- **`SlotList.tsx`** — no longer read-only. A slot with `isAvailable === false` ("תפוס")
  still renders only the time range + badge (editing/deleting a booked slot is a
  guaranteed-fail round trip, so the controls simply aren't offered — not stubbed as
  disabled buttons). A slot with `isAvailable === true` gets `lucide-react` `Pencil`/`Trash2`
  icon buttons. Edit swaps that one row's static display for an inline `<SlotForm slot=.../>`
  (`editingSlotId` local state — only one row can be in edit mode at a time). Delete calls
  `deleteAvailabilitySlot` directly with **no confirmation dialog** (low-stakes,
  easily-recreated, unlike account deletion — see `app/README.md`'s Frontend Milestone 9
  section). `SLOT_IN_USE` from either path shows a specific Hebrew message (not
  `GENERIC_ERROR_MESSAGE`) in a list-level banner and triggers a new `onRefreshNeeded`
  callback (wired to `AvailabilityPage`'s existing `loadSlots`) so the affected row's
  `isAvailable` state self-corrects once the re-fetch lands — this callback is the same
  small, doc-sanctioned addition mentioned above for `SlotForm.onConflict`.
- **Two follow-up bug fixes found and closed during live QA of the `SLOT_IN_USE` race
  condition** (QA actually reproduced the race — a slot was booked externally while its
  edit form was still open — rather than only exercising the happy path):
  1. The row being edited initially got stuck open (still showing the `SlotForm`) instead
     of collapsing back to its read-only display once the `SLOT_IN_USE` conflict came back
     — fixed.
  2. After fixing (1), the conflict message stopped rendering at all: collapsing the row
     unmounts `SlotForm` in the same React 18 batched update that would have shown its own
     local banner, so the banner never got a chance to paint. Fixed by routing the message
     through `SlotList`'s own persistent banner state instead (`SlotForm`'s new `onConflict`
     callback, see above) rather than `SlotForm` trying to show it locally right before
     unmounting. Both fixes were confirmed live afterward — the specific `SLOT_IN_USE`
     Hebrew message now reliably appears and the row correctly reflects `isAvailable: false`
     once `onRefreshNeeded` re-fetches.
- **`AvailabilityPage.tsx`** — wires `SlotList`'s new `onSlotUpdated`/`onSlotDeleted`/
  `onRefreshNeeded` props into its existing `slots` state (replace-by-id, filter-out, and
  `loadSlots` respectively); `SlotForm`'s create-mode call site updated to `onSaved`.
- **`IncomingRequestCard.tsx`** — gained a read-only 88×88px thumbnail row (`issue.images`,
  already fetched via `getIssue`, no new API call), placed after the description and before
  the accept/reject actions. Matches `shared/components/PhotoUploader.tsx`'s existing
  thumbnail sizing/`object-fit: cover` convention without reusing that component (its
  upload/remove machinery is unneeded for a read-only display). Zero images renders nothing,
  same conditional pattern already used for `issue?.description`. No lightbox. **This code is
  correct and matches the design doc exactly, but does not actually display photos for a
  real user today** — see the dedicated note immediately below.
- **Resolved, backend MS9 (2026-08-18) — was an open, unresolved issue at the time this
  round closed.** QA had found, live, in a real browser, that `GET
  /api/storage/images/**` requests issued from a plain `<img src="...">` failed with
  `net::ERR_BLOCKED_BY_ORB`, because the endpoint required a JWT bearer token and a plain
  `<img>` tag has no way to attach an `Authorization` header — confirmed at the time to be a
  systemic, cross-cutting gap, identical for every other pre-existing
  `<img src={profileImageUrl}>` usage in the app (`features/professionals/
  ProfessionalCard.tsx`, `features/professionals/ProfessionalProfilePage.tsx`,
  `features/favorites/FavoriteProfessionalCard.tsx`,
  `features/dashboard/ProfessionalProfileImageField.tsx`), not specific to this component.
  **Fixed by backend MS9**: image retrieval now issues presigned/HMAC-signed, time-limited
  URLs instead of requiring a JWT-gated `<img>` fetch (`GET /api/storage/images/**` became
  `permitAll()`, with the presigned/signed URL itself as the authorization mechanism) — no
  frontend code change was needed for this component specifically, since its `<img>` usage
  was already correct; the fix was entirely on the backend. QA live-verified, in a real
  browser, that issue photos now render both for the owning customer and for a professional
  with a confirmed order on the issue (a second, previously-undiscovered authorization gap
  also fixed in the same round — a professional was never actually authorized to view a
  customer's issue photos at all, even once the ORB bug was fixed, until backend MS9's
  `IssuesService.getById` change). Full design record:
  `docs/architecture/backend-ms9-presigned-image-urls-design.md`.
- `frontend/src/shared/api/availability.ts` gained `updateAvailabilitySlot`
  (`PUT /api/availability/slots/{slotId}`) and `deleteAvailabilitySlot`
  (`DELETE /api/availability/slots/{slotId}`).

## MS9 — dashboard/home restructure (2026-08-18)

Full design record: `docs/architecture/product-ms9-dashboard-home-design.md`. A shell/
navigation-only change — no backend change, no new components, no availability-domain
logic touched.

- **`app/router.tsx`**: `/pro` is now `<Navigate to="/pro/availability" replace />` instead
  of directly rendering `IncomingRequestsPage` — the availability calendar is now the
  professional's home screen after login (`LoginForm.tsx` and `AppLayout.tsx`'s "לוח בקרה"
  link both already targeted `/pro`, so this is satisfied without touching either file). The
  former `/pro` content moved to its own route, `/pro/requests`, matching its nav label the
  same way `/pro/jobs`/`/pro/profile` already match theirs. `/pro/jobs`, `/pro/availability`,
  `/pro/profile` are unchanged.
- **`ProDashboardLayout.tsx`/`.module.css`**: the nav is now a right-side (RTL inline-start)
  sidebar at `>=640px` — fixed `220px` width, filled/tinted active state
  (`--color-primary-light` background, reusing the same `--color-primary` token the mobile
  underline already used). At `<640px` it stays the original horizontal top-tab-bar
  presentation (`DESIGN_SYSTEM.md` §54's existing mobile pattern) — text-only labels,
  underline active state — with one addition and one QA-driven fix, both detailed in the next
  two bullets. `<Outlet />` is now wrapped in a `.content` div (`flex: 1; min-width: 0` at
  desktop) so it sits correctly beside the sidebar. The "בקשות חדשות" `NavLink`'s target
  changed from `/pro` (`end`) to `/pro/requests` (no `end`, since it's no longer matching the
  index route); the other three links are unchanged.
- **Icons**: each nav item gained a small `lucide-react` icon (`Inbox`/`ClipboardList`/
  `CalendarDays`/`User`) per the design doc's §2.3 recommendation. Icons render only at
  `>=640px` (`.tabIcon { display: none }` by default, `display: inline-flex` inside the
  `>=640px` media query) — the `<640px` tab bar stays text-only, deliberately, so adding icons
  doesn't itself widen the 4-tab strip on narrow phones.
- **QA-driven mobile-overflow bugfix**: the first pass of this milestone's `<640px` CSS was
  otherwise left untouched (per the design doc's own "mobile needs no redesign" call, §2.2),
  but QA found that on the narrowest real phone widths (320-375px) the 4 text-only tabs plus
  their gaps could still push `document.documentElement.scrollWidth` past the viewport width.
  Fixed, scoped to `@media (max-width: 639.98px)` only: the `.tabs` strip itself becomes
  `overflow-x: auto` with `flex-wrap: nowrap` (a horizontally-scrollable safety net, not a
  layout redesign), each `.tab` gets `flex-shrink: 0` plus tighter `padding-inline`/
  `font-size`, and the strip's own `gap` is reduced. This keeps any overflow contained inside
  the nav strip itself while every tab stays reachable via horizontal scroll. QA re-verified
  live afterward, at 320/375/390/414/428px: all 4 tabs present in the DOM, all reachable via
  scroll, `nav.overflowX === 'auto'` confirmed, active-state highlighting correct at every
  width. **This fix closes only the overflow this milestone's own nav restructure introduced.**
  A separate, pre-existing, out-of-scope bug remains at 320-390px: `document.documentElement.
  scrollWidth` is still a fixed ~411px at those widths regardless of the nav fix above,
  root-caused (via `git stash`) to `AppLayout.tsx`'s global header nav (`.nav` in
  `AppLayout.module.css`) — confirmed to predate this milestone and unrelated to the
  professional dashboard shell touched here. Not fixed by this pass; see `app/README.md` for
  the equivalent note on that global-header item.
- **Flagged, not resolved by this pass** (see design doc §4/§5): making the calendar the
  landing screen puts "בקשות חדשות" one click further from first paint than
  `DESIGN_SYSTEM.md` §23/`FRONTEND_AGENT.md` §37's "new requests must be immediately
  visible" guidance would otherwise suggest — an explicit, deliberate product decision for
  this task, not an oversight, but recorded here per that section's instruction. No
  pending-count badge was added to the sidebar's "בקשות חדשות" item (open question, design
  doc §5.1) — flagged as a fast-follow candidate, not built in this pass.

## Frontend Milestone 8 (2026-08-18): `/pro/profile` — professional business-profile self-service

Full design record: `docs/architecture/frontend-ms8-design.md` §2.2/§4.3.

`ProDashboardLayout` gained a 4th tab, `פרופיל` (`/pro/profile`), extending the same
established tab pattern the existing three tabs already use — `DESIGN_SYSTEM.md` §53's own
professional-dashboard-sidebar mockup already lists `▢ פרופיל` alongside the others.

- **`ProfileEditorPage.tsx`** (new) + `.module.css` — a form for the professional's
  business-listing profile: `fullName`/`serviceArea`/`city`/`bio`/`basePrice` (the exact
  `UpdateProfessionalProfileRequest` allowlist), plus a read-only `categoryId` display (via
  the existing `getCategoryNameHe` helper — **not** editable; the update DTO carries no
  field to change it, matching the backend's own deliberate exclusion). Loads via
  `getMyProfessionalProfile()` on mount, saves via `updateMyProfessionalProfile()`.
  `approvalStatus` is not rendered at all — auto-approved in v1.0 (confirmed project-wide
  rule), so it carries no actionable information today.
  - **This is a genuinely different page/concern from `app/ProfilePage.tsx`, not a
    duplicate — document this distinction clearly.** `app/ProfilePage.tsx` is a shared,
    cross-role, **read-only** display of `GET /api/users/me` (identity/account-level: name,
    email, role, default address) reachable from both roles' top-nav "הפרופיל שלי" link,
    left completely untouched by this milestone. `ProfileEditorPage.tsx` is
    PROFESSIONAL-only, reads and **writes** `professionals/me` (a business-listing profile:
    bio, city, price, photo) — a different backend package, a different DTO, a different
    concern, reached only through the professional's own dashboard, never through the
    shared top-nav profile link. The two pages intentionally do not share a data source or
    an edit affordance.
  - **`fullName` write has a cross-page staleness consequence — handled explicitly.**
    `PUT /api/professionals/me`'s `fullName` field writes to the underlying `users` row
    (not a `professionals`-only field, per that DTO's own Javadoc), so a successful save
    here also calls the new `useAuth().refreshUser()` (see below) — otherwise the top-nav's
    cached `user.fullName` (and `app/ProfilePage.tsx`'s own display) would silently go
    stale until the next full page load or re-login. This was flagged as design doc §6 Risk
    1 and closed by this addition, not left as a known gap.
- **`ProfessionalProfileImageField.tsx`** (new) + `.module.css` — a thin wrapper composing
  the existing `shared/components/ImageUploadField.tsx` for the pick/preview/remove UI, but
  — mirroring `PhotoUploader.tsx`'s existing "upload immediately on selection" pattern
  rather than `ImageUploadField`'s own default "hold a `File` for a later multipart submit"
  — calls `uploadProfessionalProfileImage(file)` as soon as a file is picked. The backend
  models the profile image as its own endpoint (`POST
  /api/professionals/me/profile-image`), independent of `PUT /me`'s field save, so there is
  no "submit the whole form together" moment to wait for. Reports the new
  `profileImageUrl` back to `ProfileEditorPage` immediately on success so the displayed
  photo updates without a full profile refetch; surfaces an inline error via
  `ImageUploadField`'s existing `error` prop on failure, the same pattern `PhotoUploader`
  uses for per-item errors.
- **`ProDashboardLayout.tsx`** — gained the 4th `NavLink` (`/pro/profile`, label `פרופיל`),
  same styling/pattern as the existing three.
- **`index.ts`** — now also exports `ProfileEditorPage`.
- **`shared/hooks/AuthProvider.tsx`**: gained a new `refreshUser(): Promise<void>` method
  (exposed via `authContext.ts`'s `AuthContextValue`), which simply re-runs the same
  `getMe()` call `login()` already does and updates `user` in place. Best-effort only — a
  failed refresh silently leaves the previous (stale but valid) `user` in place rather than
  surfacing an error to an unrelated caller. `ProfileEditorPage` is this method's first and
  (as of this milestone) only caller.

## MS10 — Profile UI Redesign (2026-08-19)

Full design record: `docs/architecture/product-ms10-profile-redesign-design.md` §2.1/§2.3.

- **`ProfessionalProfileImageField.tsx` + `.module.css` — retired, deleted.** Its job
  (photo display + upload trigger) is now `shared/components/ProfilePhoto.tsx`
  (`shared/components/README.md`), used with `onUpload` wired directly to
  `uploadProfessionalProfileImage(file)`. Upload orchestration (`isUploading`/error state)
  moved from the old wrapper component into `ProfileEditorPage.tsx` itself, since
  `ProfilePhoto` is upload-mechanism-agnostic (`onUpload` is just a callback, not tied to
  any specific endpoint).
- **`ProfileEditorPage.tsx` + `.module.css`** — photo widget swapped for `ProfilePhoto`
  (circular, centered, one edit-in-place affordance, click-to-enlarge via `ImageLightbox`
  — resolves the previous mismatched "photo with no affordance next to an unrelated 'Add
  photo' control" finding). Layout became a responsive two-region design to fix the "large
  empty area on the left" root cause (`.card { max-width: 480px }` with no width-filling,
  under `dir="rtl"` the unused space landed on the physical left): `<900px` stays a single
  column (`.card` max-width raised to `560px`, centered via `margin-inline: auto`);
  `>=900px` becomes a `240px 1fr` CSS grid (`.card` max-width raised to `880px`) — the photo
  column holds `ProfilePhoto` plus the read-only "תחום שירות" row, the form column holds
  the existing `fullName`/`serviceArea`/`city`/`bio`/`basePrice` fields, unchanged in
  meaning/validation.

## MS11 — Services & Sub-services (2026-08-19)

Full design record: `docs/architecture/product-ms11-sub-services-design.md` §5.1.

`ProfileEditorPage.tsx` + `.module.css` gained a sub-services checklist `<fieldset>`, placed
below the existing `basePrice` field and above the main save button, inside the same `.form`
column MS10 established — not a new page/route, an addition to this one.

- **Data flow**: a second, fully independent `useEffect` (alongside the existing profile
  `useEffect`) runs `Promise.all([getCategoriesWithSubServices(), getMySubServices()])` on
  mount — the full catalog (`GET /api/categories`, public but called authenticated here like
  every other call on this page) and the caller's current selection (`GET
  /api/professionals/me/sub-services`). The checklist's option set is always scoped to
  `categories.find(c => c.id === profile.categoryId)?.subServices` — a professional never
  sees another category's sub-services, reinforcing the design doc §1 distinction that this
  is a within-one-category attribute, not multi-category support.
- **Rendering**: each sub-service is a `shared/components/Checkbox` (new this milestone, see
  `shared/components/README.md`), Hebrew `nameHe` label, checked state driven by a local
  `Set<number>` of selected ids. An empty catalog for the professional's own category (no
  sub-services configured yet) renders a plain Hebrew empty-state message instead of an
  empty box.
- **Save — deliberately its own button, not merged into the main form's submit**: a compact
  secondary `"שמירת תחומי עיסוק"` `Button` (`type="button"`, so it can't accidentally trigger
  the surrounding `<form>`'s `onSubmit` even though it's nested inside the same `<form>`
  element for layout purposes), calling `updateMySubServices(Array.from(selectedIds))`
  directly. Its own independent `isSavingSubServices`/`subServicesSaveError`/
  `subServicesSavedAt` state — completely separate from the main form's `isSaving`/
  `bannerError`/`savedAt` — per the design doc's §5.1/§6 item 4 reasoning (two separate
  backend endpoints already, no shared validation, folding them into one visual "save" would
  imply one atomic operation across two unrelated API calls for no real benefit). This is an
  explicit, lead-approved product decision: the page now has two save buttons, not one.
- **`shared/api/professionals.ts` additions**: `getCategoriesWithSubServices`/
  `getMySubServices`/`updateMySubServices` — see `shared/api/README.md`'s own MS11 entry.

## MS12 — Availability UX Cleanup (2026-08-19)

Full design record: `docs/architecture/product-ms12-availability-ux-cleanup-design.md`.
Frontend-only, confined to `WeeklyAvailabilityPage.tsx`/`.module.css`; `WeeklyCalendarGrid.tsx`,
`WorkingHoursForm.tsx`, and `Modal.tsx` are unchanged.

- The post-setup branch of `WeeklyAvailabilityPage` no longer renders a permanently-visible
  working-hours list (`WorkingHoursSummary` deleted). `WeeklyCalendarGrid` is now the sole
  dominant content block, immediately visible with no 7-row list above it.
- The "עריכת שעות עבודה" entry point is now a real `Button` (`variant="secondary"`, `Pencil`
  icon) in a slim header row above the calendar, opening `WorkingHoursForm` inside the shared
  `Modal` primitive (`size="normal"`) — the same usage pattern `CalendarBlockModal.tsx`
  established. This resolves the deviation the page's doc comment previously flagged (§7.2 of
  `professional-weekly-calendar-design.md` always intended a modal; the earlier inline-expansion
  was only a stand-in before `Modal.tsx` existed).
- `isEditingHours` (boolean, drove inline expand/collapse) renamed to `isEditModalOpen`
  (drives `Modal`'s `isOpen`); `handleSaved` now closes the modal instead of collapsing an
  inline `Card`. No change to `WorkingHoursForm`'s own validation, the `PUT
  /api/availability/working-hours` full-week-replace contract, or `WeeklyCalendarGrid`'s data
  fetching/polling/click-routing.
- CSS cleanup: removed now-dead `.summaryCard`/`.summaryRow`/`.summaryDay`/`.summaryHours`/
  `.summaryOff`/`.editLink` rules from `WeeklyAvailabilityPage.module.css`. Added one small new
  rule, `.editButtonLabel` (inline-flex + gap), because the shared `Button`'s own `.label`
  wrapper has no gap defined (every other `Button` usage in this codebase so far passes plain
  text, not an icon+text pair) — needed to keep the `Pencil` icon and the Hebrew label visually
  separated inside the button.
- AVAILABLE/BLOCKED/BOOKED visual states in `WeeklyCalendarGrid` were confirmed already meeting
  the "distinct fill + icon/label, not color-only" bar per the design doc's own review — not
  touched by this pass.

## MS6 — Professional Command Center (2026-08-20)

Full design record: `docs/architecture/frontend-ms6-professional-command-center-design.md`.
The dispatch's original premise (a sidebar/calendar/photo-widget rebuild) turned out to be
stale — all three were already built (MS9-MS12) and already token-compliant, confirmed by
direct code review before this pass started (design doc §0/§2). What this milestone actually
built: a command-center summary banner, a pending-request sidebar badge, a new-request-card
entrance animation, `MyJobsPage` sectioning, a live profile-editor preview, and a role-aware
logo link (the last one documented in `app/README.md`, not here).
`WeeklyCalendarGrid.tsx`/`.module.css`, `CalendarBlockModal.tsx`, `WorkingHoursForm.tsx`, and
`shared/components/Modal.tsx` are **untouched** by this pass, per the design doc's explicit
confirmation that they're feature-complete.

- **`shared/hooks/PendingRequestsProvider.tsx` + `pendingRequestsContext.ts` + `usePendingRequests.ts`**
  (new) — a `PendingRequestsContext` mirroring `ActiveOrderProvider.tsx`/`activeOrderContext.ts`'s
  shape exactly: `usePolling(() => getMyOrders('PENDING'))` (25s interval, matching
  `WeeklyCalendarGrid`'s own `CALENDAR_POLL_INTERVAL_MS` cadence — a count badge doesn't need
  `IncomingRequestsPage`'s own 5s live-action cadence), exposing `{ count, refetch }`.
  Deliberately scoped narrower than `ActiveOrderProvider`: mounted inside
  `ProDashboardLayout.tsx` (wrapping the sidebar nav + `<Outlet />`), not in `App.tsx` — this
  data has no reason to poll outside the `/pro/*` subtree. `IncomingRequestsPage`'s own
  independent 5s poll of the same endpoint is left completely untouched; the resulting
  redundancy while `/pro/requests` is the active tab is an accepted, minor N+1-style tradeoff
  at MVP scale, consistent with this codebase's existing precedent (e.g. that same page's own
  per-issue N+1 fetch).
- **`ProDashboardLayout.tsx`/`.module.css`** — now mounts `PendingRequestsProvider` and adds a
  pending-count `Badge` (`tone="primary"`, only rendered when `count > 0`) to the "בקשות
  חדשות" `NavLink`, pushed to the tab's far (inline-end) edge via `margin-inline-start: auto`
  on `.tabBadge` so it's visible whether the nav renders as the mobile top-tab-bar or the
  desktop sidebar. Resolves the fast-follow MS9 itself flagged as unbuilt (design doc §1.3):
  making the calendar the landing screen had moved "בקשות חדשות" one click further from first
  paint, in tension with `DESIGN_SYSTEM.md` §23/`FRONTEND_AGENT.md` §37's "new requests must
  be immediately visible" guidance.
- **`CommandCenterBanner.tsx` + `.module.css`** (new) — a single restrained `Card` (not a grid
  of stat tiles, per `DESIGN_SYSTEM.md` §92) composed at the top of `WeeklyAvailabilityPage`,
  above the existing `SosAvailabilityToggle` section: a time-of-day greeting + first name
  (`useAuth().user.fullName`, same pattern `HomePage.tsx` already uses), three `Badge`s
  (pending-request count — consumes `PendingRequestsContext`, clickable through to
  `/pro/requests`; today's job count; SOS state), and an optional "העבודה הבאה" line. Today's
  job count/next-appointment come from a new, narrow, single-day
  `GET /api/availability/calendar?from=&to=` fetch — **does not** touch `WeeklyCalendarGrid`'s
  own week-range poll, a completely separate `usePolling` call scoped to this component only.
  SOS state comes from its own read-only `GET /api/availability/sos-availability` call rather
  than lifting state out of `SosAvailabilityToggle`. A lightweight command-center banner
  composed above the existing calendar was chosen over a third distinct `/pro` landing page
  (design doc §3.1) — the calendar stays `/pro`'s landing content, a professional's one mental
  model ("`/pro` opens my calendar") is preserved. Earnings are deliberately omitted — no
  backend field/endpoint returns an earnings aggregate anywhere (design doc §3.4), flagged as
  a future `GET /api/professionals/me/stats`-style candidate, not built speculatively. Motion:
  CSS-only mount transition (reuses the existing global `motion-list-item` utility class from
  `styles/motion.css` rather than a bespoke keyframe) — a static, once-per-mount informational
  card is the CSS tier per `shared/motion/README.md`, not the `framer-motion` tier.
- **`WeeklyAvailabilityPage.tsx`** — renders `<CommandCenterBanner />` first, above the
  existing SOS-toggle/working-hours/calendar content. No other change to this page's own
  state/logic.
- **`IncomingRequestCard.tsx`/`.module.css`** — gained a new `isNew: boolean` prop. When
  `true`, the card plays a one-shot `framer-motion` entrance (reuses `toastTransition`'s mount
  shape — opacity/y/scale spring — rather than a bespoke variant), respecting
  `useReducedMotion()`. Already-seen cards render with no animation — no persistent pulse/glow,
  per `DESIGN_SYSTEM.md` §91. No field/layout change; distance/ETA/customer-area remain
  correctly absent (confirmed still not backed by any real endpoint — design doc §4.2, and see
  the flagged backend-follow-up note below).
- **`IncomingRequestsPage.tsx`** — tracks previously-seen order ids across poll ticks
  (`seenOrderIdsRef`, a plain `Set<number>` diffed on every successful poll) purely to compute
  `isNew` for the entrance animation; the request list is now wrapped in `AnimatePresence`
  (`initial={false}`, so the first page-load batch doesn't all animate in at once — only
  genuinely new arrivals during an active session do). **`handleAccept`/`handleReject` and the
  polling call itself are byte-for-byte unchanged.**
- **`MyJobsPage.tsx`/`.module.css`** — the previously-flat, unsegmented order list is now
  client-side bucketed into three sections (`bucketOrders`, pure function, no new endpoint —
  still one unfiltered `getMyOrders()` call): **היום** (bookedStart today, non-terminal status,
  `ON_THE_WAY`/`CONFIRMED` sorted before a same-day `PENDING`), **עבודות עתידיות** (bookedStart
  a future date, non-terminal status, soonest-first), and **היסטוריה** (`COMPLETED`/
  `CANCELLED`/`REJECTED`/`EXPIRED` regardless of date, plus any past-dated non-terminal order
  as a catch-all — every order lands in exactly one section — sorted most-recently-`updatedAt`
  first). **Resolved product decision (design doc §5.2)**: a literal "Completed" section would
  only include `orderStatus === 'COMPLETED'`, but `CANCELLED`/`REJECTED`/`EXPIRED` orders were
  already visible on this unfiltered page before this milestone — dropping them would silently
  remove existing functionality (`FRONTEND_AGENT.md` §52), so all four terminal statuses fold
  into the one third section instead (each row still carries its own accurate `StatusBadge`).
  Each section gets its own heading and its own `EmptyState` when empty (a professional with
  jobs today but none upcoming still sees the right message in the right place) — the
  page-level empty state is kept only for the true zero-orders-ever case. No new actions: every
  row is still link-only (`/orders/:id`), on-the-way/complete stay on `OrderTrackingPage`.
- **`features/professionals/ProfessionalProfileDisplay.tsx` + `.module.css`** (new) — see
  `features/professionals/README.md`'s own MS6 entry for the full record (this component lives
  in that package, not here, since `ProfessionalProfilePage.tsx` is its primary/original
  consumer). `ProfileEditorPage.tsx`/`.module.css` (this package) is its second consumer, for
  the live unsaved-edits preview described next.
- **`ProfileEditorPage.tsx`/`.module.css`** — gained a live preview column. A same-shaped
  `ProfessionalProfileDisplayProps['professional']` object is assembled **per render, directly
  from local form state** (`fullName`/`serviceArea`/`city`/`bio`, `basePrice` parsed from its
  text input) plus the already-loaded, non-editable `profile.categoryId`/`profileImageUrl`/
  `averageRating`/`reviewCount` — no new API call, this is what makes the preview update live
  as the professional types (a newly-uploaded photo already flows in automatically via the
  existing `handlePhotoUpload` → `setProfile` call). Layout (lead-approved recommended option,
  design doc §7.2): MS10's `240px 1fr` two-column grid extends to
  `240px 1fr minmax(280px, 340px)` (photo | form | preview) at `>=900px`, preview column
  `position: sticky` so it stays visible while the (typically taller) form scrolls; below
  `900px`, the preview is a normal stacked section below the form in DOM order (no sticky,
  matching MS10's existing single-column mobile/tablet fallback). Verified correct under
  `dir="rtl"`: DOM order photo → form → preview places them right-to-left as intended (photo
  at the physical right/inline-start, preview at the physical left/inline-end), not mirrored.
- **`app/AppLayout.tsx`** — see `app/README.md`'s own MS6 entry (role-aware brand-logo link).

**Flagged, not resolved by this pass — known QA finding, duplicate `<h1>` on `/pro/profile`**:
`ProfessionalProfileDisplay` (see `features/professionals/README.md`'s own MS6 entry) renders
its own `<h1>{professional.fullName}</h1>`. Embedding it a second time here, as the live
preview, means `/pro/profile` now has 3 `<h1>` elements total (`ProDashboardLayout`'s shell
title, this page's own `PageHeader` title, and the previewed name). Not functional/blocking —
a minor document-outline/a11y cleanliness nit, recorded in `features/professionals/README.md`
for the full detail and a suggested fast-follow (a heading-level prop on the shared
component).

**Flagged, not built this pass (design doc §4.2)**: the incoming-request card's customer
area/city was confirmed absent from `OrderSummary` (the list endpoint `IncomingRequestsPage`
already fetches) — it exists on `OrderDetailResponse` but reaching it from the card would
require a third per-order `GET` on top of the existing per-issue N+1. **Decision: do not
pursue the `serviceCity`-on-`OrderSummary` backend addition as part of this frontend-only
milestone** — the card continues to omit customer area/city rather than fake it or add a
third fetch to work around the gap. Flagged here for visibility, not silently left as an
unresolved "maybe."

**Build/lint verification**: `tsc -b` and `vite build` both clean, zero new errors. `oxlint`
clean — the only warnings present are pre-existing (`qa-tmp-*` scratch Playwright scripts, one
pre-existing `ProfessionalList.tsx` fast-refresh warning), none introduced by this pass. No
browser-automation tool was available in this environment (consistent with every prior
frontend milestone) — verification beyond build/lint was a full manual code review against
the design doc, confirming §10's preserved-behavior list (accept/reject logic, booking-conflict
logic, job-status-transition logic, `WeeklyCalendarGrid`'s own polling/click-routing) holds
unchanged in every file this pass touched.

## Production Roadmap MS1 — marketplace eligibility surfaced to the professional (2026-08-22)

Design record: `docs/architecture/ms1-professional-verification-design.md` (§D-D, §D-G) and
Playbook §MS1 decision **D5**. Frontend-only changes; the eligibility rule itself is computed and
enforced entirely on the backend.

MS1 makes a professional bookable only when `approval_status = APPROVED` **and** onboarding is
complete (a category-valid sub-service, an enabled working-hours day, a verification document).
Existing professionals were never asked for sub-services or working hours at registration, so
most of them stop being listed the moment the rule takes effect. D5 forbids fabricating that data
or bulk-flipping approval states, so the product answer is discovery, not repair: tell the
professional plainly, and point them at the surfaces that already own the missing pieces.

- **`OnboardingStatusNotice.tsx` (new)** — rendered by `ProDashboardLayout` above `<Outlet />`,
  so it appears on every `/pro/*` screen (including `/pro/requests`, where a professional is most
  likely to wonder why no work is arriving). Renders **nothing at all** when the account is
  eligible. Everything it says is backend truth: `bookable` + `approvalStatus` from
  `GET /api/professionals/me` (`approvalStatus` is self-view-only per §D-G — this caller is the
  self-view), and, only when `bookable` is false, which piece is missing from
  `GET /api/professionals/me/sub-services` and `GET /api/availability/working-hours`. It links to
  the existing `/pro/profile` sub-services checklist and `/pro/availability` working-hours editor
  — **no parallel onboarding flow was built**, per D5. `usePolling` at 60s, deliberately slow and
  deliberately never disabled: it re-appears without a reload if the professional later clears
  their own sub-services (the edit endpoint allows an empty list). Rejected and
  approved-but-still-not-bookable cases state the fact without inventing a cause — a missing
  verification document is not exposed on the profile response, so the notice does not claim to
  know one.
- **`SosAvailabilityToggle.tsx`** — now reads `SosAvailabilityResponse.bookable` and, when the
  professional has the toggle on but is not eligible, adds one quiet line under "פעיל" saying no
  SOS calls will be sent until the account is completed and approved. The toggle stays fully
  usable (D4 requires ineligible professionals to keep editing everything); what changed is that
  the dashboard no longer implies they are live when `SosCandidateRepository.findEligible` can
  never select them.
- **`WorkingHoursForm.tsx` / `WorkingHoursForm.module.css`** — the 7 weekday rows, their
  validation and their request serialization moved into `shared/components`' `WeeklyHoursFields`
  + `weeklyHoursTypes` so professional registration (`features/auth`) collects the identical week
  through the identical code instead of a second copy. Markup and styles moved verbatim; this
  form's behavior, props, validation rules and `PUT /api/availability/working-hours` call are
  unchanged, including the 08:00-18:00 seed for a weekday the server hasn't configured (now
  passed explicitly as `buildWeeklyHoursRows`' `unconfiguredTimes` — registration passes nothing,
  because MS1 forbids inventing default working hours).

**Not changed, deliberately**: `ProfileEditorPage`'s sub-services checklist and
`WeeklyAvailabilityPage`'s working-hours flow are already the "complete your onboarding"
surfaces D5 asks for and needed no modification; the profile editor's doc comment note that
`approvalStatus` is "auto-approved in v1.0, no actionable approval status to surface yet" is now
stale — the live preview still passes the (self-view, therefore populated) value straight to
`ProfessionalProfileDisplay`, whose trust badge already required `=== 'APPROVED'` and so becomes
correct on its own.

## MS1 finalization — the dashboard title is gone (2026-08-22)

`ProDashboardLayout` no longer renders `<PageHeader title="לוח בקרה לבעלי מקצוע" />`. It was a
full-width title that named the *shell* rather than the screen, and it said nothing the tab strip
directly below it — or the `לוח בקרה` nav link that got you there — does not already say. The nav is
the context; each screen keeps its own section headings (`WeeklyAvailabilityPage`'s
`יומן זמינות שבועי` and `עבודות דחופות (SOS)`, and so on).

**Nothing else was removed.** No route, no nav item, no sidebar entry, no badge, no authorization
rule, and no provider mounting. `PendingRequestsProvider`/`ProSosProvider` and
`OnboardingStatusNotice` are all mounted exactly as before, and all five tabs still navigate.

**One CSS change was required and is not cosmetic drift.** `.page-container` sets *inline* padding
only — the removed `PageHeader`'s own `margin-block-end` was the sole thing separating this
dashboard from the app header. `.wrapper` now carries `padding-block: var(--space-8)`. Measured
after the change: 32 px of top spacing, 0 px horizontal overflow at 1440 px and 390 px, and the RTL
sidebar still resolving to the physical right of its content.

Validated live (MS1 report, Validations 54-56), including a screenshot review at both widths.
