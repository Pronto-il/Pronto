# Frontend MS6 — Professional Command Center: Design

Status: **design pass, not yet built.** Written by `pronto-planning` on branch
`frontend/MS6-professional-command-center` (off `92b799a`, tip of `frontend/MS1-visual-
foundation` — MS1 visual foundation + MS2 Home/Auth + MS3 Issue/AI redesign already
committed). Verified directly against real code in `frontend/src/features/dashboard/**`,
`frontend/src/features/professionals/**`, `frontend/src/app/**`, `frontend/src/shared/**`,
and the real backend DTOs in `backend/src/main/java/com/pronto/professionals/dto/**` — not
copied from prose. Cross-checked against `frontend/Pronto — DESIGN_SYSTEM.md`,
`frontend/FRONTEND_AGENT.md`, `frontend/FRONTEND_GUIDELINES.md`,
`docs/architecture/product-ms9-dashboard-home-design.md`,
`docs/architecture/professional-weekly-calendar-design.md`,
`docs/architecture/product-ms10-profile-redesign-design.md`,
`docs/architecture/product-ms11-sub-services-design.md`,
`docs/architecture/product-ms12-availability-ux-cleanup-design.md`,
`docs/architecture/frontend-ms8-design.md`, and
`frontend/src/features/dashboard/README.md`.

## 0. Headline finding — relay this to `pronto-lead`/the user verbatim

**The MS6 dispatch's premise ("top-tab layout that needs replacing with a sidebar built
from scratch") is stale, and it undersold how much of it is stale.** A right-side sidebar,
calendar-as-home routing, a fully feature-complete weekly calendar (working hours + manual
blocks + booked-order click-through, all built on `Modal`/`Button`/`StatusBadge`), and a
fully-built `ProfilePhoto`/`ImageLightbox` click-to-enlarge photo widget were **all already
built in an earlier work stream** ("old MS9–MS12", 2026-08-18/19), and — a further
correction beyond what the dispatch itself flagged — **the sidebar and calendar are also
already using MS1's current design tokens correctly**, not stale/pre-MS1 styling as the
dispatch guessed they might be. `ProDashboardLayout.module.css`/`WeeklyCalendarGrid.module.css`/
`WeeklyAvailabilityPage.module.css`/`CalendarBlockModal.module.css` have **zero** hardcoded
hex colors or off-scale spacing beyond one cosmetically-inert `color: #ffffff` (identical to
`var(--color-surface)`) and a couple of intentionally-structural pixel values (the calendar's
56px time axis, documented in its own design doc as deliberate). `Modal.tsx` itself already
received MS1's `framer-motion` treatment (mount/exit motion, `useReducedMotion()`, focus
trap) — it isn't pre-MS1 legacy at all.

**What MS6 actually is, once the already-built scope is subtracted**: a genuine
command-center summary (§3), a real content gap in the incoming-request card and the jobs
list (§4/§5), a live-preview mechanism for the profile editor (§7), and the one-line
role-aware logo fix (§8). The nav shell and the calendar need **no rebuild** — only the two
small, additive changes described in §2.

## 1. Nav/shell — what changes, what stays

### 1.1 Verified current state

`ProDashboardLayout.tsx` (`frontend/src/features/dashboard/ProDashboardLayout.tsx`) already
renders, at `>=640px`, a fixed-220px, RTL-inline-start (physical right) sidebar with a
tinted active state (`--color-primary-light` background), `lucide-react` icons
(`Inbox`/`ClipboardList`/`CalendarDays`/`User`), covering בקשות חדשות (`/pro/requests`),
העבודות שלי (`/pro/jobs`), יומן זמינות (`/pro/availability`), פרופיל (`/pro/profile`). At
`<640px` it's the documented `DESIGN_SYSTEM.md` §54 mobile pattern (horizontal tab bar, text
only). `/pro` already redirects to `/pro/availability` (`app/router.tsx` line 112). All of
this is token-compliant (verified: `ProDashboardLayout.module.css` uses `var(--space-*)`/
`var(--color-*)`/`var(--radius-sm)` exclusively).

### 1.2 ביקורות/הגדרות — decision: stay excluded, no "coming soon" treatment

Confirmed no backing screens exist for either. Recommending against adding a "coming soon"
placeholder for either: `FRONTEND_AGENT.md` §29 ("Do not add a destination to primary
navigation simply because a page exists") implies its converse holds even more strongly for
a page that explicitly *doesn't* exist, and `DESIGN_SYSTEM.md` §91 ("No Visual Noise" /
avoid excessive badges/decorative elements) argues against a disabled/grayed-out nav item
whose only function is signaling "not built yet" — it adds chrome with no user-facing value.
The dashboard's own existing code comment ("no dead nav links") already states this
reasoning; this design keeps it unchanged, not revisited.

### 1.3 The one real, additive nav change: a pending-request count badge on "בקשות חדשות"

`docs/architecture/product-ms9-dashboard-home-design.md` §4/§5 already flagged, at the time
calendar-as-home shipped, that making the calendar the landing screen moves "בקשות חדשות"
one click further from first paint — a direct tension with `DESIGN_SYSTEM.md` §23/
`FRONTEND_AGENT.md` §37's "new requests must be immediately visible" guidance — and
explicitly named a sidebar count badge as the "fast-follow candidate" that would resolve it
without re-opening the calendar-as-home decision itself. **This design builds that
fast-follow now**, because the command-center banner (§3) independently needs the same
pending-count data — building the shared data source once serves both needs at near-zero
incremental cost. See §3.3 for the concrete mechanism (a new, narrowly-scoped context
provider, not a new endpoint).

No other nav/shell change. The sidebar's visual language, breakpoint, icons, and the
mobile tab-bar fallback are otherwise **untouched**.

## 2. What genuinely needs no rebuild (confirm and move on)

- **`ProDashboardLayout.tsx`/`.module.css`**: no visual/motion rework needed beyond §1.3's
  badge. Do not re-theme, do not add page-transition motion to the sidebar itself (nav items
  don't mount/unmount — this is a CSS-hover-state case per `shared/motion/README.md`'s own
  CSS-vs-framer-motion split, not a "meaningful motion" case).
- **`WeeklyCalendarGrid.tsx`/`.module.css`, `WorkingHoursForm.tsx`, `CalendarBlockModal.tsx`,
  `Modal.tsx`**: confirmed feature-complete (working-hours setup/edit via `Modal`, read/write
  weekly grid with AVAILABLE/BLOCKED/BOOKED states each carrying a distinct fill + icon +
  text label, manual block CRUD, booked-block click-through to `OrderTrackingPage` with
  week-context preservation, SOS toggle) and already built on `Modal`/`Button`/`StatusBadge`
  with `Modal.tsx` itself already `framer-motion`-animated. See §6 for the two trivial,
  optional, non-blocking polish nits found (not required for this milestone).
- **`ProfilePhoto.tsx`/`ImageLightbox.tsx`**: already exactly match the MS6 dispatch's ask
  (click photo → full-screen `ImageLightbox` viewer; exactly one edit-in-place affordance,
  no separate "Add photo" control). No change.

## 3. Professional home — the command-center decision

### 3.1 The decision: a banner composed above the existing calendar, not a third landing page

Two options were on the table per the dispatch brief. **Decision: a lightweight
command-center banner rendered at the top of the existing `WeeklyAvailabilityPage` (which
stays `/pro`'s landing content), not a new distinct `/pro` route.**

Reasoning, ranked per `FRONTEND_AGENT.md` §63's own decision framework:
1. **User simplicity**: a professional today has one mental model — "`/pro` opens my
   calendar." Introducing a third, different landing screen (neither the calendar nor the
   request list) adds a concept, not removes one. A banner is legible as "the calendar
   screen, with a short summary on top" — no new navigation model to learn.
2. **Product consistency**: this mirrors `app/HomePage.tsx`'s own hero pattern exactly —
   greeting + a few glanceable facts, composed above the page's real content — the closest
   existing precedent in this codebase for "a command-center-feeling header," reused rather
   than reinvented.
3. **Resolves the flagged tension directly**: §1.3's pending-count badge plus this banner's
   own new-request count (§3.2, clickable through to `/pro/requests`) together restore "new
   requests immediately visible" without undoing the calendar-as-home decision — exactly the
   mitigation `product-ms9-dashboard-home-design.md` §4 flagged as available but unbuilt.
4. **Maintainability/reuse**: a banner is a small, self-contained new component with its own
   narrow data fetch (§3.3) — it does not require lifting `WeeklyCalendarGrid`'s already-
   working internal poll/state out of a feature-complete, tested component (§2), which a
   third full landing page would have made much harder to avoid touching.

**Alternative considered and rejected**: a fully separate `/pro` summary page (distinct from
both the calendar and the request list), with the calendar demoted to `/pro/availability`
only. Rejected — it would fragment the professional's mental model into three homes instead
of one, duplicate at least the "next appointment" and "today's jobs" facts the calendar
already visually encodes, and cost more engineering (a new route, a new empty/loading/error
state trio) for a benefit the banner-on-existing-page approach already delivers.

### 3.2 What the banner actually shows — every data point checked against a real endpoint

| Data point | Real? | Source |
|---|---|---|
| Greeting ("בוקר טוב, {שם פרטי}") | Yes | `useAuth().user.fullName` — same pattern `HomePage.tsx` already uses (`שלום, {firstName}`). |
| New-request count | Yes | `GET /api/bookings/orders/me?status=PENDING` (`getMyOrders('PENDING')`) — the exact endpoint `IncomingRequestsPage` already uses; count = `orders.length`. Clickable → navigates to `/pro/requests`. |
| Today's jobs count | Yes | `GET /api/availability/calendar?from=&to=` for today's date only — `BOOKED` segments whose day matches today. A **new, narrow, self-contained fetch** (today's single-day range), not a change to `WeeklyCalendarGrid`'s own week-range poll (§2 — that component stays untouched). |
| Next appointment (time + short label) | Yes | Same today-range calendar fetch — earliest `BOOKED` segment with `startAt >= now`. Label uses `StatusBadge`-style status text already established; no new field invented. |
| Availability status (SOS on/off) | Yes | `GET /api/availability/sos-availability` — the exact endpoint `SosAvailabilityToggle` already uses; the banner makes its own read-only call rather than lifting state out of that already-working component. |
| **Earnings summary** | **No dedicated field — dropped from this pass** | See §3.4. |

### 3.3 Data mechanism: a new, narrowly-scoped context provider mirroring `ActiveOrderProvider`

The pending-request count is needed in two places at once — the sidebar badge (§1.3) and
the banner (§3.2) — both nested under `ProDashboardLayout`. Rather than two independent
polls of the same endpoint at two different call sites, or prop-threading through
`react-router`'s `useOutletContext`, this design mirrors an **already-established, working
pattern in this exact codebase**: `shared/hooks/ActiveOrderProvider.tsx` +
`activeOrderContext.ts` (a `usePolling`-backed React context, mounted once, consumed by
multiple descendants).

**New**: `PendingRequestsContext`/`PendingRequestsProvider` (`shared/hooks/`), same shape:
`usePolling(() => getMyOrders('PENDING'))`, exposing `{ count, refetch }`. **Mounted inside
`ProDashboardLayout.tsx`** (wrapping `<Outlet />`), **not** in `App.tsx` — deliberately
scoped narrower than `ActiveOrderProvider` (which is CUSTOMER-gated but still mounted
app-wide for the floating indicator that can appear on any page). This data has no reason to
poll outside the `/pro/*` subtree, so scoping the provider to `ProDashboardLayout` avoids an
unnecessary background poll for a professional who isn't currently in their dashboard at
all (e.g. viewing `/orders/:id` from a notification-bell click-through, outside
`ProDashboardLayout`'s tree).

Poll interval: recommend matching the calendar's own 25s cadence (`CALENDAR_POLL_INTERVAL_MS`
in `WeeklyCalendarGrid.tsx`) — a count badge doesn't need `IncomingRequestsPage`'s own
5s live-action cadence. **`IncomingRequestsPage`'s existing 5s poll is left completely
untouched** — this creates one accepted, minor redundancy (two concurrent polls of the same
endpoint while `/pro/requests` is the active tab: the layout-level 25s one and the page's own
5s one), consistent with this codebase's already-established "N+1/redundant-fetch tolerance
at MVP scale" precedent (e.g. `IncomingRequestsPage`'s own per-issue N+1 fetch). Not worth
threading a shared cache through for a two-person-team MVP.

### 3.4 Earnings summary — explicitly dropped, not faked

No dedicated backend field or endpoint returns an earnings aggregate anywhere
(`ProfessionalProfileResponse`/`OrderSummary`/`OrderDetailResponse` all checked directly —
none exists). `OrderSummary.finalPrice` is real per-order, but assembling "earnings today"
from it would require either (a) a new backend aggregate endpoint (out of scope for a
frontend design pass — would need its own design/sign-off) or (b) client-side summing over
`GET /api/bookings/orders/me`'s full, unpaginated order history filtered to
`COMPLETED`+today client-side, which is real-but-wasteful (fetches every historical order to
compute one number) and does not belong in a banner that should render fast. **Decision:
drop earnings from the command-center banner entirely for this pass.** Flagged as a future
candidate for a dedicated `GET /api/professionals/me/stats`-style endpoint if the product
wants it — not designed further here, per this project's "flag the shape, don't build it
speculatively" convention (mirrors how `product-ms11-sub-services-design.md` §4 handled an
analogous out-of-scope extension).

### 3.5 Visual design

One `Card`, not a grid of stat tiles (`DESIGN_SYSTEM.md` §92 warns against "8 statistics
cards"/"widgets" even though professional UI may use dashboard patterns "where appropriate"
— a single restrained summary row stays inside that allowance without tipping into generic
SaaS-dashboard territory):

```
┌──────────────────────────────────────────────────────────┐
│ בוקר טוב, יוסי 👋                                          │
│ [Badge: 3 בקשות חדשות →]  [Badge: 2 עבודות היום]  [SOS: פעיל]│
│ העבודה הבאה: היום, 14:00                                    │
└──────────────────────────────────────────────────────────┘
```

- The "X בקשות חדשות" `Badge` (tone `"primary"` when count > 0, `"neutral"` when 0) is
  itself a link/button to `/pro/requests` — reuses `Badge` (`shared/components/Badge.tsx`),
  not a new pill component.
- "היום" job count and "SOS: פעיל/כבוי" use the same `Badge` primitive, `tone="neutral"`/
  `tone="info"` respectively — no new colors invented (`DESIGN_SYSTEM.md` §56's "do not
  assign new colors independently" rule).
- "העבודה הבאה" line renders only when a next appointment exists; omitted entirely
  otherwise (no "no jobs today" placeholder clutter — matches `EmptyState`'s own "helpful,
  not just absent" convention without needing the full `EmptyState` component for one line).
- Motion: **CSS only**, a simple opacity/translate mount transition consistent with this
  being a static, non-interactive-on-mount informational card, not a state-change or
  mount/exit-driven surface — the CSS-vs-framer-motion split in `shared/motion/README.md`
  places "simple opacity toggles" and "any effect naturally expressed as `transition:`" in
  the CSS tier. `pageTransition` (framer-motion) is reserved for full route transitions,
  which this isn't — the banner mounts once as part of `WeeklyAvailabilityPage`'s own
  existing render tree, not as an `AnimatePresence`-gated conditional surface.
- New component: `CommandCenterBanner.tsx` (`features/dashboard/`), composed at the top of
  `WeeklyAvailabilityPage.tsx`, above the existing `SosAvailabilityToggle` section (greeting
  first, then the existing SOS-toggle/calendar content unchanged below it).

## 4. Incoming request card redesign

### 4.1 Fields already real (no change needed)

Category (`getCategoryNameHe(issue.categoryId)`), SOS tag (`issue.urgencyType === 'SOS'`),
description quote, photo thumbnail row (`issue.images`), date/time (`order.bookedStart`/
`bookedEnd`), price (`order.finalPrice`) — all already rendered by `IncomingRequestCard.tsx`
today, sourced from real fields on `OrderSummary`/`IssueDetailResponse`. **"Expected price"
and "requested time" from the dispatch's ask are already fully satisfied** by `finalPrice`
and `bookedStart`/`bookedEnd` — no new work needed for those two.

### 4.2 Fields confirmed absent — must be dropped or flagged, not fabricated

- **Distance/ETA**: confirmed absent from every relevant type (`OrderSummary`,
  `IssueDetailResponse`, `OrderDetailResponse`). `OrderResponse.expectedArrivalAt` exists but
  is only ever set at the `ON_THE_WAY` transition (`docs/architecture/active-booking-
  floating-indicator.md`) — structurally `null` for every `PENDING` order, which is the only
  state this card ever renders. **Drop these two fields from the card entirely** — do not
  render a placeholder or fabricate a value, per `FRONTEND_AGENT.md` §9/§10.
- **Customer area/city**: confirmed absent from `OrderSummary` (the list endpoint this
  card's parent page already fetches). It **does** exist on `OrderDetailResponse`
  (`serviceCity`/`serviceStreet`/etc., the same 7-field snapshot `OrderTrackingPage` already
  renders) — but reaching it from this card would require a **third** per-order `GET
  /api/bookings/orders/{orderId}` call, stacked on top of the existing per-issue N+1 this
  page already does. **Recommendation, flagged per `FRONTEND_AGENT.md` §9 ("if frontend
  requirements need backend changes, clearly identify them")**: the small, real, low-risk fix
  is a backend addition of `serviceCity` (city only, not the full address — decision-relevant
  context, not enough detail to leak the full address before acceptance) to `OrderSummary`.
  This is genuine new backend scope, not built in this design pass — **the card omits
  customer area/city until that lands**, it does not fake it or add a third fetch to work
  around the gap.

### 4.3 Attention animation for new requests

Per MS1's "CSS for micro-interactions, framer-motion for meaningful motion" split
(`shared/motion/README.md`): a **new order arriving** in the polled list is a real product
state change, not a hover/press micro-interaction — this belongs in the `framer-motion` tier.

**Decision**: wrap the request list in `AnimatePresence` and give each card a one-shot
entrance animation (reuse the existing `listStagger`/a card-level variant shaped like
`toastTransition`'s mount) when it's newly appended — detected by diffing the previous poll
tick's order-id set against the current one (`IncomingRequestsPage` already tracks `orders`
in state; a `Set<number>` of previously-seen ids, updated after each successful poll, is a
small, local addition). **Not** a persistent looping pulse/glow on existing cards —
`DESIGN_SYSTEM.md` §91 explicitly warns against "animated backgrounds"/decorative movement,
and a one-shot entrance already communicates "this one just showed up" without an ongoing
distraction while the professional is trying to read the card's content. This is consistent
with `listStagger`'s own doc comment describing it as the "meaningful product motion" tier's
list-entrance mechanism.

## 5. Jobs page — Today/Upcoming/Completed sectioning

### 5.1 Current state (confirmed)

`MyJobsPage.tsx` is a flat, unsegmented, link-only list (`GET /api/bookings/orders/me`, no
status filter) — confirmed via direct read, matches the README's own description exactly.
Genuinely new structuring work, no existing precedent to reuse beyond the row/`StatusBadge`/
`Link` pattern already established on this same page.

### 5.2 Sectioning logic — client-side bucketing, no new endpoint

No new endpoint needed — `MyJobsPage` keeps its existing single unfiltered `getMyOrders()`
call; sectioning is a pure client-side derivation over already-fetched `OrderSummary[]`
(date bucketing off `bookedStart`, no invented data):

- **Today**: `bookedStart` falls on today's calendar date (local time, matching every other
  date-bucketing already in this app — see `WeeklyCalendarGrid.tsx`'s own documented
  timezone-assumption precedent), status not in `{CANCELLED, REJECTED, EXPIRED}`.
- **Upcoming**: `bookedStart` is a future date, same status exclusion.
- **Completed / history**: **judgment call, stated explicitly, not silent** — a literal
  reading of "Completed" would only include `orderStatus === 'COMPLETED'`. But
  `CANCELLED`/`REJECTED`/`EXPIRED` orders are already shown today (the page has no status
  filter) — dropping them from the sectioned page would silently remove functionality that
  exists today, which `FRONTEND_AGENT.md` §52 ("Preserve Existing Working Behavior") warns
  against. **Decision**: fold all four terminal statuses into this third section (each order
  still carries its own accurate `StatusBadge`, so nothing is mislabeled as "completed" when
  it was actually cancelled), sorted most-recent-first. If `pronto-lead`/the user wants a
  strictly literal "Completed" bucket with cancelled/rejected/expired elsewhere (e.g. a 4th
  section), that's a one-line filter-predicate change to this same design, not a
  restructuring — flagged here so the choice is visible, not buried.
- Within **Today**, apply a light status-hierarchy emphasis: an order in `ON_THE_WAY` or
  `CONFIRMED` sorts before a same-day `PENDING` one (reuses `StatusBadge`'s existing
  status-color mapping for the visual cue — no new colors). This satisfies the "status
  hierarchy" half of the dispatch's ask without inventing a new visual language.
- Each section gets its own heading (reuse `WeeklyAvailabilityPage.module.css`'s existing
  `.sectionTitle`/`.sectionHeaderRow` pattern for visual consistency across the dashboard)
  and its own `EmptyState` (`shared/components/EmptyState.tsx`) when empty, rather than one
  page-level empty state — a professional with jobs today but none upcoming should still see
  the "אין עדיין עבודות עתידיות" message in the right place, not a blank section.
- **No new actions added.** Accept/reject/on-the-way/complete stay exactly where they are
  today (`OrderTrackingPage`, reached via each row's existing `/orders/:id` link) — this is a
  sectioning/structuring change only, per the dispatch's own framing (point 5), not a scope
  expansion into inline actions.

## 6. Availability — confirmed scope: visual/motion alignment only, with two trivial optional nits

Confirmed feature-complete against the MS6 dispatch's "Availability" bullet (working-hours
setup + later edit via `Modal`, AVAILABLE/BLOCKED/BOOKED grid with distinct fill+icon+label
per state, manual block CRUD, booked-order click-through with week-context preservation, SOS
toggle) — **no redesign needed.** Two purely cosmetic, non-blocking nits found during review,
listed for completeness only (not required for this milestone, safe to fix opportunistically
or skip):

1. `WeeklyCalendarGrid.module.css` `.dayChipActive { color: #ffffff }` — a literal hex value
   identical in effect to `var(--color-surface)`; harmless, but should use the token if
   `pronto-coding` is already touching this file for another reason.
2. A handful of font-sizes (`11px`/`13px`) fall slightly outside `DESIGN_SYSTEM.md` §10's
   named type scale (12/14/16/17/20/24/32/40) — pre-existing, from before this scale was
   formalized in MS1's token file. Cosmetically negligible at these sizes (calendar segment
   micro-labels), not worth a dedicated pass.

No functional, structural, or booking-logic change to this feature area in this milestone.

## 7. Profile editor — live preview

### 7.1 Mechanism: extract a shared presentational component, feed it draft state or real API state

**New**: `ProfessionalProfileDisplay.tsx` (`features/professionals/`, co-located next to
`ProfessionalProfilePage.tsx` — mirrors `ReviewList.tsx`'s own "co-locate until there's a
second consumer" precedent already established in this exact module, per
`frontend-ms8-design.md` §5). Extracted from `ProfessionalProfilePage.tsx`'s currently-inline
identity block + info card + bio card JSX (photo, name, category, rating row, service-area/
city/price rows, bio) — the part that's genuinely duplicative between "the real public page"
and "a preview of unsaved edits." **Not** extracted: the favorite button, the reviews
section, or the "select professional" CTA — those are live-page-only concerns (an unsaved
draft has no favorite state, no review history of its own, and nothing to "select") and stay
inline in `ProfessionalProfilePage.tsx`, composed around the new shared component.

```ts
export interface ProfessionalProfileDisplayProps {
  professional: Pick<
    ProfessionalProfileResponse,
    'fullName' | 'categoryId' | 'serviceArea' | 'city' | 'bio' | 'basePrice' |
    'profileImageUrl' | 'averageRating' | 'reviewCount'
  >;
}
```

- **`ProfessionalProfilePage.tsx`** (modified): renders `<ProfessionalProfileDisplay
  professional={professional} />` in place of its current inline identity/info/bio JSX,
  still followed by its own favorite button, reviews section, and select CTA — zero change
  to its data-fetching, favorite-toggle, or select-CTA logic.
- **`ProfileEditorPage.tsx`** (modified): assembles a same-shaped object **per render, from
  local form state** (`fullName`/`city`/`bio` from the controlled inputs' current values,
  `basePrice` parsed from the price input) plus the already-loaded, non-editable
  `profile.categoryId`/`profile.profileImageUrl`/`profile.averageRating`/`profile.reviewCount`
  (rating/reviews aren't edited on this page, so they're read from the last-fetched `profile`
  object, not from form state) — **no new API call**, this is exactly what makes the preview
  "live" as the professional types. A newly-uploaded photo already flows into
  `profile.profileImageUrl` via the existing `handlePhotoUpload` → `setProfile` call, so the
  preview picks it up automatically with no extra wiring.

### 7.2 Placement — a real layout decision, flagged for visibility

MS10 (`product-ms10-profile-redesign-design.md` §2.3) already built a carefully-width-tuned
`240px 1fr` two-column grid at `>=900px` (photo | form). Adding a live preview reopens that
layout. Two options, recommendation stated:

- **Recommended**: extend to a three-column grid at `>=900px` —
  `grid-template-columns: 240px 1fr minmax(280px, 340px)` (photo | form | preview), preview
  column `position: sticky` so it stays visible while the form scrolls. Below `900px`, the
  preview renders as a normal stacked section below the form (no sticky — no room, consistent
  with MS10's existing single-column mobile/tablet fallback).
- **Alternative (simpler, lower-risk)**: render the preview as its own `Card` stacked below
  the full form (and below the sub-services checklist) at every width, no grid change at all.

Recommending the three-column approach for a genuinely more useful "as-you-type" preview
experience (visible without scrolling past the whole form), but flagging this explicitly
since it's the one part of this design with real layout risk on an already-shipped,
carefully-tuned page — worth a quick nod from `pronto-lead`/the user before `pronto-coding`
starts, not a silent pick.

### 7.3 "Experience" field — confirmed absent, dropped

Checked `UpdateProfessionalProfileRequest.java`/`ProfessionalProfileResponse.java` directly
— fields are exactly `fullName`/`serviceArea`/`city`/`bio`/`basePrice` (allowlisted) plus
derived/read-only `id`/`categoryId`/`profileImageUrl`/`averageRating`/`reviewCount`/
`approvalStatus`/`favorited`/timestamps. No "experience"/"years in business" field exists
anywhere in the backend. **Omitted from both the editor and the preview** — not fabricated,
per `FRONTEND_AGENT.md` §9/§10. Flagged as a real, plausible future backend field if the
product wants it (not designed further here).

## 8. Role-aware logo link

Confirmed: `AppLayout.tsx` line 56, unconditional `<Link to="/">`. `useAuth().user.role` is
already used in the same file (lines 65/71) to branch other nav links. One-line-ish change,
consistent with the existing pattern:

```tsx
<Link to={user?.role === 'PROFESSIONAL' ? '/pro' : '/'} className={styles.brand} aria-label="Pronto">
```

Targets `/pro` (which redirects to `/pro/availability`, §3.1's unchanged landing route) —
**not** a new dedicated route, matching the existing "לוח בקרה" nav link in this same file,
which already targets `/pro` for exactly this reason. `CUSTOMER`/logged-out unchanged (`/`).

## 9. Component/file-level plan

| File | Status | Notes |
|---|---|---|
| `features/dashboard/ProDashboardLayout.tsx`/`.module.css` | **Modified (small)** | Add pending-count `Badge` to the "בקשות חדשות" `NavLink`, consuming the new `PendingRequestsContext` (§3.3). No other change. |
| `shared/hooks/PendingRequestsProvider.tsx` + `pendingRequestsContext.ts` | **New** | Mirrors `ActiveOrderProvider.tsx`/`activeOrderContext.ts`'s shape exactly, scoped to `ProDashboardLayout` (not app-wide). |
| `features/dashboard/CommandCenterBanner.tsx` + `.module.css` | **New** | §3 — greeting + 3 `Badge`s + next-appointment line, own narrow today-range calendar fetch + SOS-availability fetch, consumes `PendingRequestsContext` for the request count. |
| `features/dashboard/WeeklyAvailabilityPage.tsx` | **Modified (small)** | Renders `<CommandCenterBanner />` above the existing `SosAvailabilityToggle` section. No other change — `WeeklyCalendarGrid`/`WorkingHoursForm`/`Modal` usage untouched. |
| `features/dashboard/WeeklyCalendarGrid.tsx`/`.module.css`, `CalendarBlockModal.tsx`, `WorkingHoursForm.tsx`, `shared/components/Modal.tsx` | **Untouched** | Confirmed feature-complete, §6. |
| `features/dashboard/IncomingRequestCard.tsx`/`.module.css` | **Modified (small)** | Wrap in `AnimatePresence`-driven entrance for newly-appended cards (§4.3). No field changes (distance/ETA/area already absent and correctly not rendered). |
| `features/dashboard/IncomingRequestsPage.tsx` | **Modified (small)** | Track previously-seen order ids to detect newly-appended orders for §4.3's entrance animation. Polling/accept/reject logic **unchanged**. |
| `features/dashboard/MyJobsPage.tsx`/`.module.css` | **Modified** | §5 — Today/Upcoming/Completed client-side sectioning, per-section `EmptyState`, light in-section status ordering. Data fetch (`getMyOrders()`, unfiltered) and link-only/no-actions behavior **unchanged**. |
| `features/professionals/ProfessionalProfileDisplay.tsx` + `.module.css` | **New** | §7.1 — extracted shared presentational block. |
| `features/professionals/ProfessionalProfilePage.tsx`/`.module.css` | **Modified (small)** | Composes the new `ProfessionalProfileDisplay` in place of inline JSX; favorite/reviews/CTA logic unchanged. |
| `features/dashboard/ProfileEditorPage.tsx`/`.module.css` | **Modified** | §7.1/§7.2 — live preview composed from draft form state via `ProfessionalProfileDisplay`; layout extended to a 3-column grid at `>=900px` (or stacked, per §7.2's flagged alternative). Existing form/sub-services-checklist/photo-upload logic **unchanged**. |
| `app/AppLayout.tsx` | **Modified (one line)** | §8 — role-aware logo `Link`. |
| `shared/api/bookings.ts` (`OrderSummary`) | **Flagged, not built** | §4.2 — recommend backend add `serviceCity` to `OrderSummary`; card omits customer-area until that lands. |
| `docs/architecture/api-contract-professionals-reviews.md`, `docs/architecture/api-contract-bookings.md` | **No change in this pass** | Only touched if/when the §4.2 backend flag is separately approved and built. |
| `frontend/src/features/dashboard/README.md`, `frontend/src/features/professionals/README.md`, `frontend/src/shared/hooks/README.md`, `frontend/src/app/README.md` | **Documentation follow-ups** | For `pronto-documentation` once built — new `PendingRequestsProvider`/`CommandCenterBanner`/`ProfessionalProfileDisplay`, `MyJobsPage` sectioning, `AppLayout` logo change. |

## 10. Migration/compatibility notes

This is UX/visual/navigation-structure work, not a logic rewrite. Explicitly confirmed
preserved, unchanged:

- **Accept/reject**: `IncomingRequestsPage.handleAccept`/`handleReject`, `acceptOrder`/
  `rejectOrder` calls — untouched. §4.3 only adds a presence-diff for animation purposes
  around the existing poll result.
- **Booking-conflict logic**: server-side exclusion constraints, `AvailabilityDerivationService`,
  `WeeklyCalendarGrid`'s click-routing (`AVAILABLE`→create, `BLOCKED`→edit,
  `BOOKED`→navigate-only) — zero changes, §6/§2.
- **Job-status transitions** (Confirmed → On the Way → Completed, Cancel): all live on
  `OrderTrackingPage.tsx`, reached via unchanged `/orders/:id` links from both
  `IncomingRequestCard`'s (nonexistent, accept/reject are inline) and `MyJobsPage`'s rows —
  §5's sectioning only changes which section a row's `Link` appears under, never the link's
  destination or the actions available once there.
- **No backend endpoint is added, removed, or changed by this design.** The one flagged
  backend recommendation (§4.2, `serviceCity` on `OrderSummary`) is explicitly **not** built
  as part of this pass — it needs its own sign-off and design touch if/when approved.

## 11. Open questions requiring lead/user input before implementation (resolved — see below)

**Status update (2026-08-20, `pronto-documentation`): all four items below were decided during
implementation and are now built. This section is kept as a historical record of the questions
as originally posed; each item's resolution is recorded inline. Treat this milestone's design
as closed, not pending.**

1. **§7.2 profile-editor preview layout** — 3-column sticky grid (recommended) vs. simpler
   stacked-below-form. Needs a quick preference call, not a blocker either way.
   **Resolved: the 3-column sticky grid (the recommended option) was built as designed.**
   `ProfileEditorPage.module.css` extends MS10's `240px 1fr` grid to
   `240px 1fr minmax(280px, 340px)` (photo | form | preview) at `>=900px`, with the preview
   column `position: sticky`; below `900px` it stacks below the form with no sticky, per §7.2's
   own fallback. See `features/dashboard/README.md`'s MS6 section.
2. **§5.2 "Completed" section scope** — fold `CANCELLED`/`REJECTED`/`EXPIRED` into the same
   third section (recommended, preserves existing visibility) vs. a stricter literal
   "Completed"-only bucket with a 4th section for the rest. Small either way, but changes the
   section count from 3 to possibly 4.
   **Resolved: the recommended option was built** — `MyJobsPage.tsx`'s `HISTORY_STATUSES`
   folds `COMPLETED`/`CANCELLED`/`REJECTED`/`EXPIRED` into one third section ("היסטוריה"),
   not a 4th strict section. See `features/dashboard/README.md`'s MS6 section.
3. **§4.2 customer-area/city on the incoming-request card** — needs an explicit decision on
   whether to pursue the flagged backend addition (`serviceCity` on `OrderSummary`) as a
   small follow-up, or accept the field's permanent absence from this card. Not blocking this
   milestone (the card already renders correctly without it), but worth a decision so it
   doesn't silently linger as an unresolved "maybe."
   **Resolved: not pursued.** The `serviceCity`-on-`OrderSummary` backend addition was
   declined for this pass — out of scope for a frontend-only milestone. `IncomingRequestCard`
   continues to omit customer area/city rather than fake it or add a third per-order fetch to
   work around the gap. If the product wants this later, it needs its own backend-touching
   design/sign-off, same as originally flagged here.
4. **§1.3/§3.3 pending-count badge** — confirms and builds MS9's previously-flagged,
   previously-unbuilt fast-follow. Flagging once more here in case the original deferral was
   intentional for a reason not visible to this design pass (e.g. a deliberate decision to
   keep the sidebar minimal) rather than simply "not gotten to yet."
   **Resolved: built.** The original MS9 deferral was confirmed to be "not gotten to yet," not
   a deliberate minimalism decision — `PendingRequestsProvider`/`usePendingRequests`
   (`shared/hooks/`) now back a pending-count `Badge` on the sidebar's "בקשות חדשות" `NavLink`
   (`ProDashboardLayout.tsx`), resolving the fast-follow `product-ms9-dashboard-home-design.md`
   §4/§5 flagged as available but unbuilt.

No other ambiguity found blocking this design. Everything else maps onto real, already-
verified endpoints/components with no invented backend behavior.
