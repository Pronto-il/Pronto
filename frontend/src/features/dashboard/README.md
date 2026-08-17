# features/dashboard

## Purpose
The professional-facing dashboard.

## Responsibilities
- Availability management UI (backed by the `availability` backend package).
- Incoming requests view (Standard and SOS booking requests awaiting accept/reject).
- Job-status update actions (Confirmed -> On the Way -> Completed, plus Cancel).

## Status
**Partially implemented, Frontend Milestone 3 (2026-08-16); post-QA bug-fix pass
(2026-08-17)**: incoming-request accept/reject, a read-only job history, and
availability-slot create/list. Job-status progression (On the Way / Completed) is **not**
built this pass — out of this milestone's scope (see the brief's explicit exclusion list).

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
  here — intentionally read-only, job-status progression stays out of scope.
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

Not built here: job-status action buttons (on-the-way/complete), SOS-availability toggle,
the fuller professional dashboard sidebar (ביקורות/הגדרות — no backing screens yet).
