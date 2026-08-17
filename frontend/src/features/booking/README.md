# features/booking

## Purpose
The Standard and SOS booking flows: choosing a professional, confirming a booking, and
tracking its status.

## Responsibilities
- Standard flow: professional list with each professional's own price offer, booking
  confirmation.
- SOS flow: urgent-availability professional list (reuses `features/professionals`
  components with urgent filtering rather than a separate screen), SOS request handling,
  price breakdown with the flat SOS surcharge disclosed before confirmation.
- Accept/reject handling from the customer's perspective once a professional responds.
- Confirmation / tracking screen showing booking status (Pending, Confirmed, On the Way,
  Completed, Cancelled, Expired) — status only, no GPS/map (out of scope for v1.0).

## Status
**Standard and SOS flows implemented.** Standard flow: Frontend Milestone 3 (2026-08-16);
post-QA bug-fix pass (2026-08-17). SOS flow: Frontend Milestone 4 (2026-08-17).

**Frontend Milestone 4 (2026-08-17) — SOS flow:**
- `SosBookingFlowPage` (`/issues/:issueId/sos-booking`, `CUSTOMER`-only) mirrors
  `BookingFlowPage`'s step-machine pattern but with 3 steps instead of 4 — SOS has no
  slot-picking step, the order's `bookedStart` is set to `now()` server-side and
  `bookedEnd` stays `null`: service address (`AddressFormFields`, reused unmodified) →
  professional list (`features/professionals`'s `ProfessionalList`, unmodified, calling
  `GET /api/bookings/sos-professionals` instead of `.../professionals`) → confirmation
  (`SosBookingSummary`, `POST /api/bookings/sos-orders`) → a calm success state. Shows an
  "SOS פעיל" banner (DESIGN_SYSTEM.md §49 exact copy) on every step but the success screen.
  Maps `409 ISSUE_URGENCY_MISMATCH` (reachable via a manually-edited URL to this route for
  a non-SOS issue) and `409 ISSUE_NOT_BOOKABLE` to honest Hebrew messages on the listing
  call, same pattern as `BookingFlowPage`.
- `SosBookingSummary` owns the `POST /api/bookings/sos-orders` call and its own
  double-submission guard. No date/time row (no scheduled slot). Shows a price breakdown
  (base price + flat SOS surcharge = estimated total) per DESIGN_SYSTEM.md §49's
  fee-disclosure requirement — the surcharge is a frontend constant
  (`SOS_SURCHARGE_AMOUNT = 50`, flagged in-code as a placeholder that must be kept in sync
  with the backend's `BookingsService.SOS_SURCHARGE_AMOUNT`, since no endpoint exposes that
  value ahead of order creation). The real `finalPrice`/`sosSurcharge` from `OrderResponse`
  is what's shown after creation (success screen / `/orders/:id`, unchanged). Maps `409
  SOS_PROFESSIONAL_UNAVAILABLE` (the professional toggled off between listing and this
  call) — bounces the customer back to a re-fetched professional list, mirroring
  `BookingSummary`'s `SLOT_UNAVAILABLE` handling — and `409 ISSUE_NOT_BOOKABLE`, to
  specific Hebrew messages.
- `features/issues/IssueSuccessStep.tsx`'s SOS branch now routes into
  `/issues/${issueId}/sos-booking` (previously a stub with "not available yet" copy).

**Post-QA fixes (2026-08-17):**
- `OrderTrackingPage`'s back button now checks the caller's role (`useAuth`) and
  navigates to `/orders` for a `CUSTOMER` or `/pro` for a `PROFESSIONAL`, instead of
  always going to the `CUSTOMER`-only `/orders` (which silently redirected a professional
  to `/`).
- `BookingFlowPage`'s `LISTING_ERROR_MESSAGES` now also maps `409 ISSUE_URGENCY_MISMATCH`
  (reachable only via a manually-edited URL to `/issues/:issueId/booking` for an SOS
  issue) to an honest Hebrew message instead of falling back to the generic error banner.

- `BookingFlowPage` (`/issues/:issueId/booking`, `CUSTOMER`-only) is the step machine:
  service address (`AddressFormFields`, reused unmodified per its own doc comment) →
  professional list (`features/professionals`'s `ProfessionalList`, `GET
  /api/bookings/professionals`) → slot picker (`SlotPicker`, `GET
  /api/bookings/professionals/{id}/slots`) → confirmation (`BookingSummary`, `POST
  /api/bookings/orders`) → a calm success state. Mirrors
  `features/issues/NewIssuePage.tsx`'s step-union/back-navigation pattern. The address is
  collected once and threaded through: query params on the listing call, and
  `serviceCity`/`serviceStreet`/`serviceHouseNumber`/`serviceApartment` on order creation —
  `floor`/`entrance`/`addressNotes` (also collected by `AddressFormFields`) are not
  forwarded, since the booking endpoints don't accept them (accepted simplification, not a
  bug).
- `SlotPicker` groups the flat `AvailabilitySlotItem[]` response by calendar day
  client-side (the API has no grouping of its own) into a date-chip row + time-chip grid,
  per DESIGN_SYSTEM.md §46-47. Every slot the API returns is already future/available, so
  nothing is ever rendered as a disabled/unavailable chip.
- `BookingSummary` owns the actual `POST /api/bookings/orders` call (mirrors `ReviewStep`'s
  self-contained pattern) and its own double-submission guard (button `loading` state).
  Maps `SLOT_UNAVAILABLE` (bounces the customer back to a re-fetched slot picker) and
  `ISSUE_NOT_BOOKABLE` to specific Hebrew messages; anything else falls back to the generic
  error copy.
- `OrderTrackingPage` (`/orders/:orderId`, either role) uses the new `useOrderStatus` hook
  (`shared/hooks`, short-polling per `overview.md` §3.3, stops once the order reaches a
  terminal status) + the new shared `StatusBadge`. Shows professional name, status,
  date/time, address, price. A customer-only cancel action appears while the order is
  `PENDING`/`CONFIRMED`/`ON_THE_WAY` (the exact actor/state matrix from
  `api-contract-bookings.md` §2.7) — no cancel action is built for the professional side
  this pass (not in this milestone's brief). No ETA/GPS field is shown here — Milestone 8's
  `etaMinutes` lives only on the professional-listing card, not on `OrderDetailResponse`.
- `MyOrdersPage` (`/orders`, `CUSTOMER`-only per the route map) lists the caller's own
  orders (`GET /api/bookings/orders/me`), each row linking to `/orders/:id`. Empty state
  per DESIGN_SYSTEM.md §60, CTA back to `/issues/new`.

Not built here: job-status action buttons beyond cancel (on-the-way/complete are
professional-only and belong to a future professional job-status screen, not
`features/dashboard`'s scope either), slot edit/delete UI, favorites toggle interaction,
reviews UI.
