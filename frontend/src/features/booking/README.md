# features/booking

## Purpose
The Standard and SOS booking flows: choosing a professional, confirming a booking, and
tracking its status.

## Responsibilities
- Standard flow: professional list with each professional's own price offer, booking
  confirmation.
- SOS flow: urgent-availability professional list (reuses `features/professionals`
  components with urgent filtering rather than a separate screen), SOS request handling.
- Accept/reject handling from the customer's perspective once a professional responds.
- Confirmation / tracking screen showing booking status (Pending, Confirmed, On the Way,
  Completed, Cancelled, Expired) — status only, no GPS/map (out of scope for v1.0).

## Status
Stub only — no screens yet. Implemented in **Milestone 3 — Standard booking flow** (core
flow) and **Milestone 4 — SOS booking flow** (`docs/architecture/implementation-plan.md`),
against the backend `bookings` package.
