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
`SosBookingFlowPage` (`GET /api/bookings/sos-professionals`). **Frontend redesign MS4 —
Booking & Professional Marketplace (2026-08-20, see below)**: `ProfessionalList` gained
`listStagger` entrance motion and `EmptyState` reuse; `ProfessionalProfilePage` gained a
`Skeleton` loading state.

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

## Frontend redesign MS4 — Booking & Professional Marketplace (2026-08-20)

Full design record: `docs/architecture/frontend-ms4-booking-marketplace-design.md`. Status:
**implemented, QA-signed-off** (`pronto-qa`, 61/61 assertions passed, zero application bugs
found — see `features/booking/README.md`'s MS4 section for the full QA record covering both
packages). Working tree on branch `frontend/MS4-booking-marketplace`, uncommitted — not
pushed/merged; that remains the user's own explicit git action. This milestone's audit found
this package already token-compliant and functionally complete (design doc §1) — the actual
work in this package was two consistency/polish items, both scoped to
`ProfessionalList.tsx`/`.module.css` only:

- **`listStagger` entrance animation (design doc §3.A2)**: `ProfessionalList`'s result `.list`
  now wraps in a `motion.div` using `shared/motion/variants.ts`'s `listStagger` as the
  container variant, with each `ProfessionalCard` wrapped in a small item-level `motion.div`
  reusing `pageTransition`'s existing fade/rise shape (no new named variant needed) — the
  first real consumer of the `listStagger` "list entrance beyond the simple CSS case" use case
  that variant's own doc comment named. Respects `variants.ts`'s documented ~8-item
  stagger-cap guideline (`STAGGER_CAP = 8`): only the first 8 cards get the per-item motion
  wrapper, the rest render without it. Gated on `useReducedMotion()` the same way
  `IssueSuccessStep.tsx`/this milestone's new `BookingSuccessStep.tsx` already are (the
  `animate` target is overridden directly, not the component-level `transition` prop, since
  each variant embeds its own spring transition).
- **`EmptyState` component reuse (design doc §3.C2)**: the hand-rolled `.empty`/`.emptyTitle`
  zero-results markup was replaced with `<EmptyState title="לא נמצאו בעלי מקצוע פנויים"
  description="אפשר לנסות שוב מאוחר יותר." />` — same component `MyOrdersPage.tsx`
  (`features/booking`) was also switched to this milestone, see that package's own README.
- **Not touched in this package this milestone**: `ProfessionalCard.tsx`,
  `ProfessionalProfileDisplay.tsx`, `ReviewList.tsx` — confirmed already token-compliant and
  DESIGN_SYSTEM-matching by the design doc's own file-by-file audit (§1), no rebuild needed.
  `ProfessionalProfilePage.tsx`/`.module.css` did change this milestone, but only for its own
  loading-state skeleton (below) — its favorite-toggle/review-fetch/select-CTA logic is
  unchanged.
- **`ProfessionalProfilePage.tsx`'s `Skeleton` loading state (design doc §3.B)**: the bare
  `<p>טוען…</p>` identity/info/bio loading state was replaced with a small avatar
  (`Skeleton variant="circle"`) + two text-line placeholders (`Skeleton variant="text"
  lines={2}`), sized to roughly match the real identity block. The same `shared/components`
  `Skeleton` primitive already used elsewhere in this app (`ProfessionalList.tsx`,
  `StartTimePicker.tsx` in `features/booking`) — no new component.
- **`AddressSelectionStep.tsx`'s chip-toggle → `FilterChipGroup` swap (design doc §3.C1)** and
  the `MyOrdersPage.tsx` Active/History sectioning IA change both live in `features/booking`,
  not here — documented in that package's own README MS4 section, since neither touches this
  package's files.

## MS4 final corrections — visual pass (2026-08-20)

Full record: `docs/architecture/frontend-ms4-booking-marketplace-design.md` §4b F3. The MS4
audit had cleared this package on token compliance, which was correct as far as it went — a
live look at the rendered screens found hierarchy/trust gaps a stylesheet reading cannot see.
Verified live (43/43 Playwright assertions, desktop + mobile 390×844 + RTL + reduced motion).

- **`ProfessionalCard` — §29 hierarchy.** The profession line was missing (`serviceArea` sat
  in its place). The card now takes an optional `categoryId` (passed down by
  `ProfessionalList` from the listing response — every professional in a listing matches the
  issue's category) and renders `getCategoryNameHe(categoryId)` directly under the name;
  `serviceArea` moved into the meta strip next to the distance, where the other location
  signal already lives. ETA + service area + distance now sit on a tinted strip so they scan
  as facts about the visit rather than more body copy. The favourite marker became a real
  `Heart` icon with an accessible label instead of a bare `♥` character.
- **`ProfessionalCard` — §33's recommended badge.** It did not exist anywhere in the app, even
  though MS1 had built `Badge`'s `tone="primary"` specifically for it (that component's own doc
  comment names §33). The card now takes `isTopRecommendation`; `ProfessionalList` sets it for
  `index === 0` **only while the `RECOMMENDED` sort is active** — the backend's own top-ranked
  result, never a client-side score — and the card gets the badge plus a tinted ring. §32 holds:
  emphasis changes with the sort, structure does not. This supersedes the MS4 design doc §1
  table's claim that omitting the badge was correct: there is no `recommended` *field* to
  fabricate, but the `RECOMMENDED` *sort order* is real backend output.
- **`ProfessionalCard` — honest empty rating.** An unrated professional previously rendered
  nothing where the rating goes, leaving a hole in the hierarchy; it now says
  `עדיין אין ביקורות`, which is a statement of fact, not a trust claim (§44).
- **`ProfessionalProfileDisplay` — §43/§44 trust.** The page carried no trust signal beyond an
  optional rating. It now renders a `בעל מקצוע מאומת` badge gated on
  `approvalStatus === 'APPROVED'` and a §43-style stats strip (average rating / review count /
  "בפרונטו מאז" month-year), built strictly from fields `ProfessionalProfileResponse` actually
  returns. No ETA stat (needs a customer address this page doesn't have) and no completed-jobs
  stat (no endpoint exposes one) — those two of §43's three stats are honestly absent rather
  than faked. An unrated professional's stat shows `—`, never `0.0`. Both new fields are
  **optional** in the props type, so `features/dashboard`'s `ProfileEditorPage` preview keeps
  compiling; that preview now passes them through too, so a professional sees what customers
  see. Note the badge currently renders for every professional, because `Professional`'s
  entity sets `approvalStatus = 'APPROVED'` on creation (v1.0 has no approval workflow) — it is
  backend-truthful today and will discriminate on its own if an approval workflow ever lands.
  The **listing card deliberately shows no verification mark**: its DTO has no such field and
  the listing filters only on "not deleted" (`BookingsService.isProfessionalActive`), so a
  checkmark there would be an unsupported claim (§44).
- **`ProfessionalProfilePage` copy bug.** The favourite button's active label read
  `הוסר ממועדפים` — past-tense passive ("was removed"), i.e. a status message where the button's
  own action belongs. Now `הסרה ממועדפים`.
- **`ReviewList` empty state** moved from a bare `<p>` to the shared `EmptyState`.
- **Hebrew plural.** `{n} ביקורות` renders as the ungrammatical `1 ביקורות` at exactly one
  review. A shared `formatReviewCount` (`shared/utils/hebrewText.ts`) returns `ביקורת אחת`
  instead — the same class of fix `formatRelativeAgeLabel` already carries for `1 חודשים`.
  Applied to `ProfessionalCard`, `ProfessionalProfileDisplay`, and
  `features/favorites`'s `FavoriteProfessionalCard` (same §31 format, same bug — fixed there
  too rather than left inconsistent).

## MS6 — Professional Command Center (2026-08-20): `ProfessionalProfileDisplay` extraction

Full design record:
`docs/architecture/frontend-ms6-professional-command-center-design.md` §7.1/§7.2.

- **`ProfessionalProfileDisplay.tsx` + `.module.css`** (new) — the identity block (photo,
  name, category, rating row) + info card (service area/city/price) + bio card, extracted
  from `ProfessionalProfilePage.tsx`'s previously-inline JSX — the part that's genuinely
  duplicative between "the real public page" and "a live preview of unsaved edits"
  (`features/dashboard/ProfileEditorPage.tsx`, this component's second consumer). Co-located
  next to `ProfessionalProfilePage.tsx`, mirroring `ReviewList.tsx`'s own "co-locate until
  there's a second consumer" precedent already established in this exact module.
  ```ts
  export interface ProfessionalProfileDisplayProps {
    professional: Pick<
      ProfessionalProfileResponse,
      'fullName' | 'categoryId' | 'serviceArea' | 'city' | 'bio' | 'basePrice' |
      'profileImageUrl' | 'averageRating' | 'reviewCount'
    >;
  }
  ```
  **Not** extracted: the favorite button, the reviews section, or the "select professional"
  CTA — those are live-page-only concerns (an unsaved draft has no favorite state, no review
  history of its own, and nothing to "select") and stay inline in
  `ProfessionalProfilePage.tsx`, composed around this component.
- **`ProfessionalProfilePage.tsx`/`.module.css`** — now renders
  `<ProfessionalProfileDisplay professional={professional} />` in place of the extracted JSX.
  The favorite button, previously nested inside the identity block, is now a sibling below it
  (`.favoriteButtonWrapper`, a new small wrapper rule reproducing the same centered/gapped
  layout it previously got "for free" from being nested inside `.identityBlock`) — zero change
  to the favorite-toggle logic itself, or to the review-fetching/select-CTA logic. The moved
  CSS rules (`.identityBlock`/`.photo`/`.photoFallback`/`.name`/`.category`/`.rating`/
  `.ratingStar`/`.reviewCount`/`.infoCard`/`.row`/`.rowLabel`/`.rowValue`/`.bioTitle`/
  `.bioText`) now live in `ProfessionalProfileDisplay.module.css` instead.
- **`index.ts`** — now also exports `ProfessionalProfileDisplay`/
  `ProfessionalProfileDisplayProps`, consumed cross-feature by
  `features/dashboard/ProfileEditorPage.tsx` — see that package's README for the live-preview
  layout (a 3-column sticky grid at `>=900px`) built around this component.

**Known, flagged-but-not-resolved-by-this-pass QA finding: duplicate `<h1>` on `/pro/profile`.**
`ProfessionalProfileDisplay` renders its own `<h1>{professional.fullName}</h1>` (identity
block). `ProfessionalProfilePage` (its original consumer) already carries a pre-existing,
unrelated instance of the same "two `<h1>`s per page" pattern — its own `PageHeader` title
("פרופיל בעל מקצוע") plus the identity block's name-`<h1>` — dating from before MS6 (this
extraction moved that pre-existing pair into a separate component without changing the count
on this page). What MS6 newly introduces is a **third** consumer of the same name-`<h1>`:
`ProfileEditorPage.tsx` embeds `ProfessionalProfileDisplay` as its live-preview column, and
since that page is itself nested inside `ProDashboardLayout`'s own `PageHeader`-titled shell,
`/pro/profile` ends up with **three** `<h1>` elements on the page at once:
`ProDashboardLayout`'s dashboard-shell title ("לוח בקרה לבעלי מקצוע"), `ProfileEditorPage`'s
own title ("פרופיל עסקי"), and the previewed professional's name inside
`ProfessionalProfileDisplay`. Not a functional bug — QA found no visual/interaction defect —
but a document-outline/accessibility cleanliness nit (multiple `<h1>`s per page is invalid
HTML-outline practice), worth a fast-follow, e.g. giving `ProfessionalProfileDisplay` a
heading-level prop (`h1`/non-heading `<p>`, chosen by the consumer) so only one real
page-level `<h1>` exists at a time. Flagged, not fixed, by MS6.
