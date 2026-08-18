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
Implemented, Frontend Milestone 3 (2026-08-16); sort-toggle behavior corrected in the
MS3/MS4 product-corrections pass (2026-08-17, see below). Consumed by both
`features/booking/BookingFlowPage` (`GET /api/bookings/professionals`) and
`SosBookingFlowPage` (`GET /api/bookings/sos-professionals`).

- `ProfessionalCard` renders identity (photo with an initials fallback avatar, name,
  service area), rating + review count (omitted entirely when `averageRating` is `null` —
  never rendered as "0 reviews"), distance + ETA, price, and a single primary CTA
  ("בחירת בעל מקצוע"), per DESIGN_SYSTEM.md §29-33. Accepts a `sort` prop that only shifts
  visual emphasis (`sort === 'RECOMMENDED'` bolds the rating, `sort === 'CHEAPEST'` bolds
  the price, `sort === 'FASTEST'` bolds the ETA) — the card structure never changes between
  sort modes (FRONTEND_AGENT.md §12). The `FASTEST` branch is unreachable dead code given
  neither flow's chips can currently produce that value (see below) — left in place, costs
  nothing, and is immediately reusable if `FASTEST` is ever wired to a chip later.
- `ProfessionalList` owns the sort-chip row (DESIGN_SYSTEM.md §34) plus the results-count
  heading (§42), a skeleton loading state, and an empty state. Sort state itself is owned by
  the caller (`BookingFlowPage`/`SosBookingFlowPage`), since selecting a sort re-triggers the
  listing call with the new `sort` value. **`STANDARD_SORT_OPTIONS`/`SOS_SORT_OPTIONS`** (both
  exported from `ProfessionalList.tsx`) are **identical, 2-value arrays**:
  `[RECOMMENDED, CHEAPEST]`, Recommended shown first, label "הכי מומלצים"/"הזולים ביותר" —
  one shared chip vocabulary across both booking flows, both defaulting to `CHEAPEST`. This
  was reconciled in the MS3/MS4 product-corrections pass (2026-08-17): the backend's
  `ProfessionalSort` enum genuinely has a third value, `FASTEST` (a real, working
  rating-independent ETA-ascending ranking — not removed, not a placeholder), but no chip in
  either flow currently exposes it — see
  `docs/architecture/ms3-ms4-corrections-design.md` §3 for the full reconciliation record
  (an earlier, uncommitted draft of this same work had briefly given SOS a different,
  `Recommended | Fastest` chip pair with `FASTEST` as its default; that was corrected before
  the branch was finalized).
- `favorited`/`reviewCount`/`averageRating` come from the Milestone 8 backend enrichment on
  `ProfessionalCard` (the API DTO) and are rendered read-only — no favorite-toggle button
  built this pass (that needs `POST`/`DELETE /api/favorites`, explicitly out of scope). A
  small passive heart indicator is shown next to the name when `favorited` is true.

Not built here: a dedicated professional-profile detail screen (DESIGN_SYSTEM.md §43 — no
route/screen for it yet, the booking flow goes straight from the list to the slot picker), a
user-facing `FASTEST`/"fastest first" sort option in either flow (see above).
