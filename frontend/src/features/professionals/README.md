# features/professionals

## Purpose
Professional card/list components shared by the Standard and SOS booking flows.

## Responsibilities
- Professional card component (profile summary, price offer where applicable,
  availability/urgent-availability indicator).
- Professional list rendering, reused by `features/booking` for both Standard listing and
  SOS urgent-filtered listing (per PRD §7.4 — SOS is a filtered reuse of this component,
  not a separate screen).

## Status
Implemented, Frontend Milestone 3 (2026-08-16), Standard-listing consumer only —
`ProfessionalCard`/`ProfessionalList`, consumed by `features/booking/BookingFlowPage`'s
professional-listing step (`GET /api/bookings/professionals`).

- `ProfessionalCard` renders identity (photo with an initials fallback avatar, name,
  service area), rating + review count (omitted entirely when `averageRating` is `null` —
  never rendered as "0 reviews"), distance + ETA, price, and a single primary CTA
  ("בחירת בעל מקצוע"), per DESIGN_SYSTEM.md §29-33. Accepts a `sort` prop that only shifts
  visual emphasis (price vs. ETA) — the card structure never changes between sort modes
  (FRONTEND_AGENT.md §12).
- `ProfessionalList` owns the Cheapest/Fastest sort-chip row (§34) — there is no
  server-side "Recommended" third mode, so only those two chips are offered — plus the
  results-count heading (§42), a skeleton loading state, and an empty state. Sort state
  itself is owned by the caller (`BookingFlowPage`), since selecting a sort re-triggers the
  `GET /api/bookings/professionals?sort=` call.
- `favorited`/`reviewCount`/`averageRating` come from the Milestone 8 backend enrichment on
  `ProfessionalCard` (the API DTO) and are rendered read-only — no favorite-toggle button
  built this pass (that needs `POST`/`DELETE /api/favorites`, explicitly out of scope). A
  small passive heart indicator is shown next to the name when `favorited` is true.

Not built here: SOS urgent-filtered reuse (Milestone 4/SOS frontend scope, not this
milestone's), a dedicated professional-profile detail screen (DESIGN_SYSTEM.md §43 — no
route/screen for it yet, the booking flow goes straight from the list to the slot picker).
