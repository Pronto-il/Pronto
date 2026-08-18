# features/professionals

## Purpose
Professional card/list components shared by the Standard and SOS booking flows, plus (as of
Frontend Milestone 8) the standalone professional-profile detail screen and its review list.

## Responsibilities
- Professional card component (profile summary, price offer where applicable,
  availability/urgent-availability indicator).
- Professional list rendering, reused by `features/booking` for both Standard listing and
  SOS urgent-filtered listing (per PRD §7.4 — SOS is a filtered reuse of this component,
  not a separate screen).
- **As of Frontend Milestone 8**: a dedicated `/professionals/:professionalId` detail
  screen (`ProfessionalProfilePage.tsx`) and its review list (`ReviewList.tsx`) — see
  below.

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
  `ProfessionalCard` (the API DTO) and are rendered read-only on this card — no
  favorite-toggle button on the listing card itself (favoriting/unfavoriting an actual
  professional now happens on `ProfessionalProfilePage`, see below). A small passive heart
  indicator is shown next to the name when `favorited` is true.

Not built here: a user-facing `FASTEST`/"fastest first" sort option in either flow (see
above — still unreachable dead code by design, unchanged this pass).

## Frontend Milestone 8 additions (2026-08-18) — professional-profile detail screen, reviews, `viewProfileContext`

Full design record: `docs/architecture/frontend-ms8-design.md` §2.3/§4.1.

- **`ProfessionalProfilePage.tsx`** (new) + `.module.css` — the `/professionals/:id` detail
  screen (bare `RequireAuth`, either role, matching the backend's route-gate-free `GET
  /api/professionals/{id}`). Fetches `getProfessionalProfile(id)` and `getReviews(id)`
  independently/in parallel — a slow or failed review fetch never blocks the rest of the
  page (`ReviewList` owns its own loading/error state). Renders photo, name, category,
  numeric rating + review count (omitted when `averageRating` is `null`), service
  area/city, `bio` (when set), `basePrice`, the review list, a favorite toggle (rendered
  only for `user.role === 'CUSTOMER'`, calling `addFavorite`/`removeFavorite`, optimistic
  with revert-on-failure, initial state from `professional.favorited`), and a "select
  professional" CTA. `404 NOT_FOUND` renders a simple not-found message, no crash.
- **The "select professional" CTA — only rendered with flow context.** It appears only when
  `location.state` carries `{ fromIssueId, urgencyType }` (i.e. the page was reached via a
  `ProfessionalCard`'s identity-block link from an active booking flow — see below). A
  direct visit, a page refresh, or arriving via `/favorites` (which passes no state) all
  correctly degrade to a view-only page — a deliberate, accepted gap (state is
  intentionally non-bookmarkable), not a defect. Clicking the CTA does **not**
  reimplement booking/SOS selection: it writes into each flow's own pre-existing
  draft/resume mechanism (`updateDraft({ stage: 'SLOT_SELECTION' | 'BOOKING_CONFIRM',
  professionalId })`, unmodified) and navigates back into
  `/issues/:issueId/booking`/`.../sos-booking`, which each flow's existing resume-hydration
  effect already knows how to pick up — **zero changes to `BookingFlowPage`'s or
  `SosBookingFlowPage`'s own selection logic**.
- **`ReviewList.tsx`** (new) — renders each review's `customerName`, a 5-star rating
  (filled count = `rating`, a distinct format from the numeric "★ 4.9 · 127" aggregate this
  package's own `ProfessionalCard` already uses — both correct, used in different places),
  a relative age label (`formatRelativeAgeLabel`, `shared/utils/formatDateTime.ts`), and
  `comment` when present. An explicit empty state when there are no reviews yet.
  **Co-located here, not its own `features/reviews/` module**, since
  `ProfessionalProfilePage.tsx` is its only consumer — mirrors how other small,
  single-consumer pieces are already placed in this codebase (e.g. `BookingSummary.tsx`/
  `StartTimePicker.tsx` — renamed from `SlotPicker.tsx` by the professional weekly
  availability calendar feature M6 — living inside `features/booking` rather than their own
  modules). Reuses
  `ProfessionalProfilePage.module.css` rather than a dedicated stylesheet, for the same
  reason. Trivially movable if a second consumer ever appears.
- **`ProfessionalCard.tsx` changes — a real, load-bearing distinction, read carefully.**
  The card gained one new optional prop, `viewProfileContext?: { issueId: number;
  urgencyType: 'STANDARD' | 'SOS' }`. When provided (both `BookingFlowPage` and
  `SosBookingFlowPage` always pass it, since each already knows its own `issueId`/urgency
  type), the card's **identity block** (photo + name) becomes a `Link` to
  `/professionals/:professionalId`, carrying `{ fromIssueId: issueId, urgencyType }` as
  router `state` (not a query param — `location.state` is deliberately
  transient/non-bookmarkable, unlike a URL, since "you got here from an active,
  already-filtered booking flow" is a fact that shouldn't survive a refresh or a shared
  link). **The primary CTA button is completely unchanged**: it is still the card's own
  `[ בחירת בעל מקצוע ]` button, still wired directly to `onSelect(professional)`, still the
  only thing `BookingFlowPage`/`SosBookingFlowPage` need to handle for in-flow selection.
  The identity-block link is a **secondary**, purely additive affordance — a future reader
  should not infer that the card's core select behavior changed; it did not.
- **`index.ts`** — now also exports `ProfessionalProfilePage` (for `router.tsx`) and the
  `ViewProfileContext`/`ProfessionalDetailLocationState` types (declared in
  `ProfessionalCard.tsx`, since that's where the router `state` shape they describe is
  produced and consumed).
- **`shared/api` additions consumed here**: `professionals.ts` (new —
  `getProfessionalProfile`), `favorites.ts` (new — `addFavorite`/`removeFavorite`),
  `reviews.ts`'s new `getReviews`. See `shared/api/README.md`.
