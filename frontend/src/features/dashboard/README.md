# features/dashboard

## Purpose
The professional-facing dashboard.

## Responsibilities
- Availability management UI (backed by the `availability` backend package).
- Incoming requests view (Standard and SOS booking requests awaiting accept/reject).
- Job-status update actions (Confirmed -> On the Way -> Completed, plus Cancel).

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
link-only.

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
  inputs → `POST /api/availability/slots`) and a read-only `SlotList` (`GET
  /api/availability/slots/me`, showing each slot's `isAvailable` state). No edit/delete
  controls — out of this milestone's scope, and deliberately not stubbed as disabled
  buttons (FRONTEND_AGENT.md §53).

**Post-QA fix (2026-08-17):** `IncomingRequestCard`'s loading spinner now tracks which
action is actually in flight — `IncomingRequestsPage` stores `{ orderId, action }` instead
of a bare processing id, and the card takes separate `isAccepting`/`isRejecting` props
(previously a single `isProcessing` boolean made the Accept button show a spinner even
while Reject was the one running; both buttons still disable together during any in-flight
action).

Not built here: the fuller professional dashboard sidebar (ביקורות/הגדרות — no backing
screens yet). Job-status action buttons (on-the-way/complete) **are now built** (Frontend
Milestone 6, 2026-08-18) but belong to `features/booking/OrderTrackingPage.tsx`, not this
package — see that package's README.
