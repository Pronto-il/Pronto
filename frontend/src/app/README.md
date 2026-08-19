# app

## Purpose
Routing, layout, and root application configuration — the composition root that wires
feature modules together into the single-page app described in
`docs/architecture/overview.md` §3.1.

## Responsibilities
- Router setup (`react-router-dom`), including route-based role gating between the
  customer and professional experiences (`RequireAuth`).
- App-level layout (`AppLayout`) — shared chrome (top nav header) wrapping every route.
- Root configuration mounted from `src/main.tsx`, wiring `AuthProvider` around the router
  so auth state is available everywhere.

## Structure
- `App.tsx` — wraps `RouterProvider` in `AuthProvider` (from `shared/hooks`), which in turn
  wraps `BookingDraftProvider` (needed nested inside `AuthProvider` so it can call
  `useAuth()` internally for its cross-account leakage guard — see `shared/hooks/README.md`).
  **As of the Active Booking Floating Indicator feature**: also wraps `ActiveOrderProvider`
  (sibling-nested with `BookingDraftProvider`, since it too needs `useAuth()` internally).
- `router.tsx` — route tree. All routes render inside `AppLayout`; `/profile`,
  `/orders/:orderId`, and (**as of Frontend Milestone 8**) `/professionals/:professionalId`
  are nested under a bare `RequireAuth` (either role — matching the backend's
  route-gate-free `GET /api/professionals/{id}`); `/issues/new`,
  `/issues/:issueId/booking`, `/orders`, and (**as of Frontend Milestone 8**) `/favorites`
  are nested under `RequireAuth role="CUSTOMER"`; `/pro`, `/pro/requests`,
  `/pro/availability`, and (**as of Frontend Milestone 8**) `/pro/profile` are nested under
  `RequireAuth role="PROFESSIONAL"` and, within that, under `features/dashboard`'s
  `ProDashboardLayout`. **As of the MS9 dashboard/home change (2026-08-18,
  `docs/architecture/product-ms9-dashboard-home-design.md`)**: `/pro` is now a
  `<Navigate replace>` redirect to `/pro/availability` (the professional's home screen
  after login is now the availability calendar, not the incoming-requests feed), and the
  former `/pro` content (`IncomingRequestsPage`) moved to its own path, `/pro/requests`.
  `ProDashboardLayout` itself is a sidebar at `>=640px` (RTL inline-start edge, i.e. the
  physical right) and stays a horizontal tab bar at `<640px`, with a QA-driven mobile-overflow
  fix (the tab strip is `overflow-x: auto`-scrollable, scoped to `<640px`) — see
  `features/dashboard/README.md`'s MS9 section for the full CSS detail and its own note on a
  separate, pre-existing, unrelated overflow bug in this file's own header nav (see "Known
  issues" below).
- `AppLayout.tsx` — top nav shell (brand + login/register or profile/logout links
  depending on auth state). Frontend Milestone 3 added the first two real primary-nav
  destinations now that they exist as real screens: a customer's "ההזמנות שלי" (`/orders`)
  and a professional's own "לוח בקרה" (`/pro`) link. **As of Frontend Milestone 8
  (2026-08-18)**, revised same-day per an approved UX correction: `/favorites`
  (`features/favorites`) is deliberately **not** a top-nav link — it's a secondary customer
  feature reached via `app/ProfilePage.tsx`'s "מועדפים" link instead, not
  `DESIGN_SYSTEM.md` §52's literal desktop-nav mockup. A full mobile bottom nav (§50-51)
  still has no implementation at all and remains out of scope.
- `RequireAuth.tsx` — route guard. Redirects to `/login` when not authenticated (after
  the auth provider's initial rehydration finishes); supports an optional `role` prop to
  gate a route to one role.
- `HomePage.tsx` — placeholder home route (unchanged content since Milestone 0; only its
  wrapping element changed from `<main>` to `<div>` since `AppLayout` now owns the page's
  `<main>` landmark).
- `ProfilePage.tsx` — behind `RequireAuth`, plus logout/account-deletion. **As of the MS10
  profile redesign (2026-08-19, `docs/architecture/product-ms10-profile-redesign-design.md`
  §2.4)**: a `CUSTOMER` caller now gets a real edit form (`fullName`/`phone`/
  `defaultAddress` via the new `PUT /api/users/me`, always-editable — same "form + save
  button" pattern `features/dashboard/ProfileEditorPage.tsx` uses, not a view/edit-mode
  toggle); a `PROFESSIONAL` caller stays fully read-only (no product ask for a second
  editing surface for `fullName`, already editable at `/pro/profile`). Both roles now show
  `shared/components/ProfilePhoto` at the top — a `CUSTOMER` gets a non-upload initials
  avatar (no photo field for a customer in this milestone), a `PROFESSIONAL` sees their own
  `/pro/profile` photo read-only (`user.professional.profileImageUrl`). The old
  `justify-content: space-between` label/value row layout was replaced with "label directly
  above value." **Previously (pre-MS10)**: read-only display of `GET /api/users/me` +
  logout only. **As of the MS3/MS4 product-corrections pass (2026-08-17)**: also displays
  the customer's saved default address (`user.defaultAddress`, all 7 fields, when non-null)
  — `null` for a `PROFESSIONAL` caller or a pre-`V20` `CUSTOMER` with no recorded default,
  per that field's own "absent means no such object" convention. This was a live QA fix
  during this pass (the page previously did not render `defaultAddress` at all, even though
  the backend already returned it). **As of Frontend Milestone 9 (2026-08-18)**: gained
  account deletion, below the existing "יציאה מהחשבון" logout button — a two-step inline
  button swap (no new modal component), per `docs/architecture/frontend-ms9-gap-fixes-design.md`
  §2. Default state is a single "מחיקת חשבון" (`destructive`) button; clicking it swaps to a
  confirm/cancel pair with an irreversibility message, no API call yet. Confirming calls the
  new `deleteMe()` (`DELETE /api/users/me`, `shared/api/users.ts`); on success, ends the
  session the same way `handleLogout` already does (`useAuth().logout()` then `navigate('/login',
  { replace: true })`); on failure, shows `GENERIC_ERROR_MESSAGE` in a `role="alert"` banner
  and stays in the confirming state so the user can retry without re-initiating. **Fully
  QA-verified live, no bugs found**: QA confirmed the complete round trip against a real
  backend/Postgres instance, including DB-level confirmation that the account row's
  `deleted_at` gets set and its email gets anonymized on delete, and confirmed that a
  subsequent login attempt using the deleted account's credentials correctly fails
  afterward.
- `BookingDraftIndicator.tsx` — new, MS3/MS4 product-corrections pass. A persistent nav
  widget for an in-progress booking draft (see `shared/hooks/README.md`), rendered inside
  `AppLayout`'s nav, conditional on a draft existing (naturally never true for a
  `PROFESSIONAL` session, since issue creation/booking are `CUSTOMER`-only routes, and
  further protected by `BookingDraftProvider`'s ownerId guard against stale cross-account
  drafts). Clicking its body navigates to the draft's resume route
  (`resolveDraftRoute(draft)`); a separate dismiss icon discards the draft directly, no
  confirmation dialog (an MVP-simplicity call — discarding is low-stakes, the customer can
  simply redo the flow). Placed in `app/`, not `shared/components/`, since it's a one-off
  app-shell widget tightly coupled to `useBookingDraft()` and rendered in exactly one place.
- `ActiveOrderIndicator.tsx` — new, Active Booking Floating Indicator feature (2026-08-17).
  Reads `useActiveOrder()` + `useEtaCountdown()` (`shared/hooks`) and does nothing but map
  `selection.state` to an icon/label/click-through route via `useNavigate()` — no business
  logic of its own; the priority-selection and countdown math both live in `shared/hooks`.
  Gated on `user?.role === 'CUSTOMER'`, rendered `null` when there's no active order.
  **Structurally and visually distinct from `BookingDraftIndicator`, deliberately, not
  merged into one component**: `BookingDraftIndicator` stays exactly where it is — an inline
  chip inside `AppLayout`'s `<nav>`, alongside the profile/logout links.
  `ActiveOrderIndicator` is rendered as a separate, `position: fixed` floating circular
  element, a sibling of `<main>` in `AppLayout`'s returned tree (**not** inside `<nav>` at
  all) — so it overlays every routed page. Each reads its own independent context
  (`useBookingDraft()` vs. `useActiveOrder()`); a `BookingDraft` (client-only, no backend
  row) and an `Order` (backend row) can never represent the same booking at the same time
  (the booking-flow pages' `handleConfirmed(order)` clears the draft in the same handler
  that receives the new order), but the two indicators **can** both be visible at once for
  two different, unrelated bookings (e.g. an old completed order awaiting review
  acknowledgement, while the customer is mid-draft on a brand-new issue) — expected, not a
  conflict. Full design record:
  `docs/architecture/active-booking-floating-indicator.md` §6.3.
- `AppLayout.tsx` — **as of Frontend Milestone 5 (2026-08-18)**: also renders
  `<NotificationBell />` (`features/notifications`) inside `<nav>`, right after
  `BookingDraftIndicator` and before the role-conditional `/orders`/`/pro` link. Rendered
  for **both** roles (unlike `ActiveOrderIndicator`, CUSTOMER-only) — `GET
  /api/notifications` is an either-role, self-scoped feed, so the bell has no role gate.
  No `ProDashboardLayout` change was needed: it only renders its own `/pro/*` sub-tabs and
  an `<Outlet />`, and is itself rendered inside `AppLayout`'s top-level `<Outlet />` (see
  `router.tsx`'s nesting), so `AppLayout`'s nav — and the bell inside it — is already
  present above every `/pro/*` screen.

- `AppLayout.tsx`/`router.tsx` — **Frontend Milestone 8 (2026-08-18)**: three new routes
  (`professionals/:professionalId`, `favorites`, `pro/profile`). `/favorites` is reached via
  `ProfilePage.tsx`, **not** an `AppLayout` nav link (revised same-day per an approved UX
  correction — see "Structure" above). No new provider/context — favorites/profile-editing
  state is local to their own pages, not app-wide. Full detail:
  `docs/architecture/frontend-ms8-design.md` and `features/favorites/README.md`/
  `features/professionals/README.md`/`features/dashboard/README.md`.

## Known issues
- **`AppLayout.tsx`'s global header nav causes page-level horizontal overflow at narrow
  mobile widths (320-390px), pre-existing, not yet fixed.** QA discovered this while
  verifying the MS9 dashboard/home restructure (`features/dashboard/README.md`'s MS9
  section): even after that pass's own mobile-nav overflow fix closed its contribution,
  `document.documentElement.scrollWidth` still exceeds the viewport at 320/375/390px,
  root-caused to `.nav` in `AppLayout.module.css` (the top-bar's `BookingDraftIndicator` +
  `NotificationBell` + role-conditional link + profile link + logout button, all
  `display: flex` with no wrapping/shrinking behavior defined for narrow widths). Confirmed
  via `git stash` to predate MS9 — **not** caused by, and out of scope for, that milestone.
  Not fixed as of this writing; flagged here as a known, open, out-of-scope item so it isn't
  mistaken for a MS9 regression or silently lost.

## Status
**Frontend Milestone 3 (2026-08-16, Standard booking flow) implemented.** `ProPlaceholderPage`
was removed — the professional's `/pro` route now renders a real dashboard
(`features/dashboard`'s `ProDashboardLayout` + `IncomingRequestsPage`/`AvailabilityPage`,
**superseded — see the professional weekly availability calendar note below**).
Router now also includes `/issues/:issueId/booking`, `/orders`, and `/orders/:orderId`
(`features/booking`). Further feature routes are added here incrementally as each
milestone lands (SOS routes in Milestone 4, notifications in Milestone 5, etc.).

**Professional weekly availability calendar feature, M3-M4 (2026-08-18)**: `/pro/availability`
(route path unchanged, `router.tsx` untouched) now renders `WeeklyAvailabilityPage` instead
of the `AvailabilityPage` named above — `AvailabilityPage.tsx`/`SlotForm.tsx`/`SlotList.tsx`
remain in the repo but are orphaned, unreachable from any route. See
`features/dashboard/README.md`'s M3-M5 sections for the full detail; not otherwise restated
here since this package's own router wiring (the `element` passed to the existing
`pro/availability` route entry) is the only thing that changed.

Prior status: **Milestone 1 (Auth & user management) implemented** — the auth routes
(`/register`, `/register/customer`, `/register/professional`, `/verify`, `/login`) from
`features/auth`, plus the authenticated `/profile` route.

**MS3/MS4 product-corrections pass (2026-08-17)**: `App.tsx` gained the `BookingDraftProvider`
wrapper (see "Structure" above); `AppLayout.tsx`'s nav gained `BookingDraftIndicator`,
rendered before the role-conditional links; `ProfilePage.tsx` gained the `defaultAddress`
display (a live QA fix during this pass — see "Structure" above). No router changes.

**Active Booking Floating Indicator feature (2026-08-17)**: `App.tsx` gained the
`ActiveOrderProvider` wrapper (see "Structure" above); `AppLayout.tsx` gained
`<ActiveOrderIndicator />`, mounted as a sibling of `<main>` (outside `<nav>`, unlike
`BookingDraftIndicator`), gated on `user?.role === 'CUSTOMER'`; `router.tsx` gained one new
route, `orders/:orderId/review` → `CompletionReviewPage` (`features/booking`), nested under
the existing `RequireAuth role="CUSTOMER"` group alongside `issues/new`/`.../booking`/
`.../sos-booking`/`orders`. QA-passed (12/12 checklist items, zero bugs). Full design
record: `docs/architecture/active-booking-floating-indicator.md` §7-§8.

**Frontend Milestone 5 — Notifications (2026-08-18)**: `AppLayout.tsx` gained
`<NotificationBell />` in `<nav>` (see "Structure" above). No `App.tsx`/`router.tsx` changes
— the bell has no provider (it's a plain hook, `shared/hooks/useNotifications.ts`, not a
context) and no dedicated route (an anchored dropdown, not a page).

**Frontend Milestone 8 — Professional Profiles, Reviews & Favorites (2026-08-18)**:
`router.tsx` gained three new routes (`professionals/:professionalId`, `favorites`,
`pro/profile`) — see "Structure" above for placement/role-gating. **Same-day UX
correction**: `/favorites` was originally a CUSTOMER-only `AppLayout.tsx` nav link; per
approved UX decisions, it moved to be reachable only via `ProfilePage.tsx`'s "מועדפים"
link (secondary customer feature, not primary nav) — `AppLayout.tsx`'s nav link was
removed. The other two Milestone 8 UX decisions (professional profile editing lives under
`/pro/profile`, not the public profile; "view profile" and "select professional" are
separate actions, both reachable from the card and the detail page) were already the
as-built behavior and needed no change. No new `App.tsx` provider. QA-passed (live API
round-trip + code review); full detail in `docs/architecture/implementation-plan.md`'s
"Frontend Milestone 8" entry and `docs/architecture/frontend-ms8-design.md`.

**Frontend Milestone 9 — gap-fixes (2026-08-18)**: `ProfilePage.tsx` gained the account-
deletion two-step confirmation described above — **fully implemented and fully
QA-verified live, no bugs found**. No router/App.tsx changes. This is one of three
gap-fixes landed in this pass (branch `frontend/MS9-gap-fixes`, local only — uncommitted,
not pushed/merged); the other two (availability slot edit/delete, and a professional
seeing issue photos before accepting) live in `features/dashboard/` — see that package's
README for their status, which is **not** uniformly "done" (the issue-photos item is
code-complete but currently non-functional in-browser due to a pre-existing image-auth
gap, unrelated to and unfixed by this pass). Full design record:
`docs/architecture/frontend-ms9-gap-fixes-design.md` §2.

**MS9 — Professional Dashboard & Home (2026-08-18)**: `router.tsx` gained one routing
change — `/pro` is now `<Navigate to="/pro/availability" replace />` (the calendar is the
professional's home screen after login), and the former `/pro` content
(`IncomingRequestsPage`) moved to `/pro/requests`. This is a **different, later "MS9"** from
the one above (Frontend Milestone 9 / gap-fixes) — the two share the number only by
coincidence of separate source material; see `docs/architecture/product-ms9-dashboard-home-
design.md`'s own disambiguation note. **Fully implemented and QA-verified live**, including
a follow-up mobile-overflow bugfix found and closed during QA — see
`features/dashboard/README.md`'s MS9 section for the sidebar/nav detail (`ProDashboardLayout`
is the only other file changed; `app/`'s own change is limited to the one `router.tsx` route).
Working tree on `main`, uncommitted — not pushed/merged. QA also surfaced a separate,
pre-existing, out-of-scope overflow bug in this package's own `AppLayout.tsx` header nav —
see "Known issues" above; not fixed by this pass.

**MS10 — Profile UI Redesign (2026-08-19)**: `ProfilePage.tsx`/`.module.css` gained a
`CUSTOMER`-only edit form (see "Structure" above) — no router/`App.tsx` change. Full design
record: `docs/architecture/product-ms10-profile-redesign-design.md`.
