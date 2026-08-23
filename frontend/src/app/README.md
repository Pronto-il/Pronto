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
  **As of MS1 (2026-08-20)**: innermost, wraps `ToastProvider` around `RouterProvider`, with
  `<ToastViewport />` (`shared/components`) rendered as `RouterProvider`'s sibling inside
  that same `ToastProvider` (so `useToast()` works from any routed page and the portaled
  viewport always has a live stack to read) — see `shared/hooks/README.md`'s Toast triad
  entry. Nesting order is `AuthProvider > BookingDraftProvider > ActiveOrderProvider >
  ToastProvider > (RouterProvider, ToastViewport)`.
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
  `DESIGN_SYSTEM.md` §52's literal desktop-nav mockup.
  **As of MS2 (2026-08-20, `docs/architecture/frontend-ms2-home-auth-design.md` §3)**: a
  full mobile bottom nav now exists — `BottomNav.tsx`/`.module.css` (new), `CUSTOMER`-only +
  authenticated-only (same gating condition as `<ActiveOrderIndicator>`), 4 items per
  `DESIGN_SYSTEM.md` §50's explicit list (בית/הזמנות/מועדפים/פרופיל — not the milestone
  dispatch's own "Notifications" suggestion, which the design doc's §3.3 resolves in favor
  of the design system, since `NotificationBell`'s dropdown already covers that capability
  without a dedicated route). Desktop also got a bigger brand (header `64px`→`72px`, logo
  crop scaled ~1.167x) and a demoted, icon-only logout button (`aria-label`/`title` carry the
  meaning, no visible "יציאה" label, moved after the profile link with its own gap).
  `.desktopOnlyNav` (`display: contents` desktop, `display: none` <640px) originally wrapped
  "ההזמנות שלי"/"לוח בקרה"/"הפרופיל שלי"/logout so those destinations dropped out of the mobile
  top bar entirely (customers reach them via `<BottomNav>`; professionals still reach their
  dashboard via `ProDashboardLayout`'s own <640px tab bar, untouched by this milestone) — see
  the mobile-logout follow-up fix below, which moved logout back out of `.desktopOnlyNav`.
  `BookingDraftIndicator`/`NotificationBell` stay visible in the mobile top bar at every
  width. Two required "spillover" fixes from introducing the fixed bottom bar:
  `ActiveOrderIndicator.module.css`'s mobile `bottom` offset now clears it
  (`calc(68px + var(--space-3))`, was `var(--space-4)`), and `AppLayout.module.css`'s `.main`
  gained an unconditional mobile `padding-block-end: 68px` so the bar never occludes the last
  real content. `NotificationBell`'s dropdown anchor (`position: absolute` relative to its
  own wrapper) was verified to still work correctly from the narrower mobile top bar — no
  change needed there. `BookingDraftIndicator.module.css` also gained a `<360px` variant that
  hides its text label (icon + dismiss only) so the pill still fits next to the bell on the
  narrowest phones.
  **Mobile-logout gap, fixed in a scoped follow-up**: MS2 originally left a `PROFESSIONAL`
  session on a mobile viewport with no nav-reachable logout at all (`ProDashboardLayout`'s tab
  bar has no logout entry, out of scope; `/profile` — which does have a logout button — was
  reachable by URL but had no mobile nav link pointing to it for that role). Fixed by moving
  `.logoutButton` in `AppLayout.tsx` to render as a sibling *after* `.desktopOnlyNav` closes
  (instead of nested inside it), so it's visible at every viewport width for every
  authenticated user regardless of role — `.desktopOnlyNav`'s `display: contents` on desktop
  never introduced a box, so this has zero effect on desktop layout. `.logoutButton`'s
  `margin-inline-start` (the desktop-only "own gap" after the profile link) is overridden back
  to `0` in the existing `<640px` media query, since on mobile the button now sits directly
  after `NotificationBell` rather than the profile link, and the compact top bar has less room
  to spare.
- `RequireAuth.tsx` — route guard. Redirects to `/login` when not authenticated (after
  the auth provider's initial rehydration finishes); supports an optional `role` prop to
  gate a route to one role. **This is UX, not security** — it decides which screen a browser
  shows and protects no data; every gated API route is enforced backend-side by
  `common.security.RoleRequiredInterceptor`, which answers `403 FORBIDDEN` however the request
  was made. Stated explicitly in the file as of Production Roadmap MS1, which added the first
  `role="ADMIN"` routes.
- `HomePage.tsx` — placeholder home route (unchanged content since Milestone 0; only its
  wrapping element changed from `<main>` to `<div>` since `AppLayout` now owns the page's
  `<main>` landmark). **As of MS1 (Visual Foundation & Motion System, 2026-08-20)**: the
  mascot inside `.mascotArea` was fixed — it previously referenced a non-existent
  `/assets/pronto-runner-wrench.png` (a `public/`-relative path that never existed; the
  page rendered no image and failed silently since `alt=""`) and now renders
  `<Mascot state="running" size="lg" loop={false} />` (`shared/components/Mascot.tsx`) in
  the same position/composition. `loop={false}` is deliberate restraint, not an oversight —
  the mascot renders statically (its containing `.mascotArea` gets a single short
  rise/fade entrance via the shared `motion-list-item` CSS utility, not a continuous
  bounce). This is an asset/infra fix only, **not** a Hero redesign — headline, CTA,
  layout, and trust indicators are untouched; the full "continuously in motion, Pronto is
  on the way" hero treatment is explicitly out of scope here and deferred to MS2. The
  local `mascotIdle` keyframe and its own `prefers-reduced-motion` override were deleted
  (superseded by the global reduced-motion block in `styles/motion.css`), as was the local
  `.motionLines` markup (not needed for this static usage). **As of MS2 (2026-08-20,
  `docs/architecture/frontend-ms2-home-auth-design.md` §2)**: the full Hero redesign
  deferred by MS1 landed — an authenticated greeting line ("שלום, {first name} 👋", first
  token of `user.fullName`), the CTA panel rebuilt as a real horizontal flex row composing
  `Mascot` (`loop`, `size="lg"`) as a flex child on the inline-end side instead of an
  absolutely-positioned overlay, and a new 3-item trust-indicator row (`ShieldCheck`/`Tag`/
  `Activity`, local JSX, single consumer — no new shared component) below the panel. Mobile
  stacks the CTA panel vertically (`flex-direction: column-reverse`, mascot on top) and
  shrinks the mascot to `md`'s footprint via a CSS custom-property override
  (`--mascot-w`/`--mascot-h`/`--mascot-scale`) rather than a JS viewport check. Whole hero
  wrapped in the shared `pageTransition` `framer-motion` variant (its first real consumer);
  the mascot block additionally gets a one-shot `mascotSlideIn` entrance. Deliberately still
  does **not** build `DESIGN_SYSTEM.md` §35's "Popular services"/"Active booking" sections —
  see the design doc §2.7 for why (active booking is already served by
  `ActiveOrderIndicator`; popular services needs new product decisions out of this
  milestone's scope).
- `DesignSystemPage.tsx` — **new, MS1 (2026-08-20).** Dev-only visual QA/showcase route
  (`/__design`) rendering every token/primitive MS1 introduced or upgraded — type scale,
  shadows, `Button`/`Card`/`Input`-family states, `Badge` tones, `FilterChipGroup`,
  `Skeleton` variants, `EmptyState` (both tones), a `Modal` trigger (both
  `mobilePresentation` values), `Toast` triggers (via `useToast()`), `PageHeader` with
  `steps`, and all 6 `Mascot` states × 4 sizes on 3 backgrounds. Wired in `router.tsx` as a
  top-level sibling of the `AppLayout` route tree (not nested under it — needs no app
  chrome/auth) via a `designSystemRoutes` array that's only non-empty when
  `import.meta.env.DEV` is true, so the route is absent from the route table and, per
  Vite's dead-code elimination on that statically-replaced literal, from the production
  bundle entirely. Renders only real components through their existing public APIs — no
  new props/behavior were added to any component just to make a section demonstrable. Not
  a product page; not part of any route table documented elsewhere in `docs/architecture`.
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
- `AppLayout.tsx` — **MS6 Professional Command Center (2026-08-20)**: the brand logo `Link` is
  now role-aware — `<Link to={user?.role === 'PROFESSIONAL' ? '/pro' : '/'}>` — matching the
  existing "לוח בקרה" nav link right next to it, which already targets `/pro` for the same
  reason. `CUSTOMER`/logged-out still go to `/`. One-line change, no other `AppLayout`/router
  change from this milestone (its other work is entirely inside `features/dashboard`/
  `features/professionals` — see those packages' READMEs).

- `router.tsx`/`AppLayout.tsx`/`ProfilePage.tsx` — **Production Roadmap MS1, professional
  verification (2026-08-22, `docs/architecture/ms1-professional-verification-design.md` §D-F)**:
  a third `RequireAuth` group, `role="ADMIN"`, wrapping two new routes —
  `/admin/professionals` (the operator review queue) and
  `/admin/professionals/:professionalId` (one application + approve/reject). Both render
  `features/admin`; see that package's README. Own top-level path prefix rather than a section
  of `/pro/*` or `/profile`, mirroring the backend's own split — `/api/admin/professionals/**`
  are the only `ADMIN`-gated routes in the app, and a distinct prefix keeps "which routes have
  which audience" answerable by reading the path. `AppLayout.tsx` gained one `ADMIN`-only nav
  link ("אימות בעלי מקצוע", `ShieldCheck`) inside `.desktopOnlyNav`, and its role-aware brand
  link now sends an `ADMIN` to `/admin/professionals` (the same treatment `PROFESSIONAL` already
  had for `/pro`) — that link is also the operator's only nav route into the surface below
  640px, where `.desktopOnlyNav` is hidden; acceptable for a desktop-first operator tool.
  `features/auth/LoginForm.tsx` lands an `ADMIN` on the queue after login rather than the
  customer home page. `ProfilePage.tsx`'s `ROLE_LABELS` gained `ADMIN: 'מפעיל מערכת'` (required
  — the map is `Record<UserRole, string>` and `UserRole` gained a third member); that page's
  existing read-only branch already renders correctly for the role with no other change.
  `ActiveOrderIndicator`/`BottomNav` are `CUSTOMER`-gated and were therefore already correct.
  **The route guard is UX, not security** — see `RequireAuth.tsx` above.

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
  mistaken for a MS9 regression or silently lost. **Update, MS2 (2026-08-20)**: MS2's mobile
  top-bar changes (`.desktopOnlyNav` now `display: none` <640px, dropping the role-conditional
  link/profile link/logout button from the mobile row entirely) substantially shrink the
  mobile nav's content and likely improve or resolve this, but it was **not** re-verified via
  QA in this pass — left as an open item for QA to re-check rather than claimed fixed.

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

**MS1 — Visual Foundation & Motion System (2026-08-20)**: `App.tsx` gained the
`ToastProvider`/`ToastViewport` wiring described above; `router.tsx` gained the dev-only
`/__design` route (`DesignSystemPage.tsx`, new file, gated behind `import.meta.env.DEV`);
`HomePage.tsx`/`.module.css` had their broken mascot reference fixed (see "Structure"
above) — the one sanctioned product-page touch in this milestone, deliberately restrained
(no Hero redesign, that's MS2). No other route/provider change. This is an earlier-numbered
milestone landing after several later-numbered ones above (MS9, MS10) chronologically —
"MS1" here refers to the frontend visual-foundation redesign's own milestone sequence
(MS1-MS6), a separate numbering track from the feature-delivery "Frontend Milestone N"/
product "MSN" entries elsewhere in this file; see `shared/components/README.md`'s MS1
entry for the full list of new/upgraded primitives this unblocks.

**MS2 — Home + Authentication Experience (2026-08-20,
`docs/architecture/frontend-ms2-home-auth-design.md`)**: `HomePage.tsx`/`.module.css` got
the full Hero redesign (see "Structure" above); `AppLayout.tsx`/`.module.css` got the
desktop brand/logout treatment, the mobile top-bar content reduction, and a new
`BottomNav.tsx`/`.module.css`; `ActiveOrderIndicator.module.css`/
`BookingDraftIndicator.module.css` got small required spillover-fix tweaks (see "Structure"
above for all of the above). `features/auth/*` got a parallel, larger redesign (progressive
multi-stage registration wizards, a new `RegistrationWizardShell` shared component, a `phone`
field P0 bug fix, and a Login/RegisterChoice visual pass) — see `features/auth/README.md`
for that package's own detail, not restated here. Working tree on
`frontend/MS1-visual-foundation`, uncommitted — not pushed/merged.

**MS2 QA bugfix pass (2026-08-20)**: two real bugs QA found in the above work, fixed as part
of this same milestone (not new scope):
- **`prefers-reduced-motion` not respected on `HomePage.tsx`'s new `pageTransition`
  (hero)/`mascotSlideIn` (mascot) usage.** The fix pattern this milestone's own
  `RegistrationWizardShell` and MS1's `Modal.tsx`/`ToastViewport.tsx` appear to use — reading
  `useReducedMotion()` and passing an instant `{ duration: 0 }` via the component's
  `transition` prop — turned out **not to actually work** for any variant whose own `animate`
  target embeds a `transition` (as `pageTransition`/`mascotSlideIn`/`stepTransition` all do):
  framer-motion gives a variant's own target-embedded `transition` precedence over the
  component-level `transition` prop, so that prop is silently ignored whenever the variant
  defines one. Confirmed live via Playwright (`reducedMotion: 'reduce'` context, rAF-sampled
  opacity): the un-guarded pages still ran the full ~300ms spring. The actual working
  technique — verified by the same method to match `Modal.tsx`/`ToastViewport.tsx`'s real
  runtime behavior — overrides the `animate` **target object** itself (not the `transition`
  prop) when `shouldReduceMotion` is true, e.g. `animate={shouldReduceMotion ? { ...variant.animate, transition: { duration: 0 } } : 'animate'}`.
  Applied to `HomePage.tsx` (hero + mascot). `RegistrationWizardShell.tsx` itself was **not**
  touched (out of this bugfix's explicit scope) — it likely has the same latent issue on its
  stage-transition `stepTransition` usage; flagged to `pronto-lead`, not silently fixed here.
- **`NotificationBell`'s dropdown panel clipped off-screen on the mobile top bar.** Root cause
  and fix live in `features/notifications/README.md` (the file itself is owned by that
  package, not this one) — noted here because the regression was caused by this milestone's
  own `AppLayout.module.css` narrowing the mobile top bar.

Both verified with real Playwright runs against the dev server + a live backend/Postgres
account (build and lint also re-run clean); see `features/auth/README.md`/
`features/notifications/README.md` for the per-package fix detail.

## MS1 finalization — profile screen cleanup (2026-08-22)

Two presentation-only changes to `ProfilePage.tsx`, both scoped to what the screen *shows*.

**1. The internal user role is no longer shown to end users.** The `סוג משתמש` row rendered `לקוח`
to a customer and `בעל מקצוע` to a professional — the system describing its own `users.role` column
back at someone who cannot act on it and did not ask. Removed for both.

`ADMIN` **keeps** the row: an operator's profile is otherwise near-identical to a professional's
read-only one, and confirming which account a privileged session is on is operationally useful.
`ROLE_LABELS` is now a `Partial<Record<UserRole, string>>` holding only the `ADMIN` entry, so
re-adding an end-user label is a visible edit rather than a lookup that quietly starts resolving
again.

This changes **nothing** about authorization. `users.role` is untouched, no DTO or schema changed,
registration is untouched, and every decision that reads the role still reads it — this component's
own customer/professional branch, `RequireAuth`, and `AppLayout`'s nav all still switch on
`user.role`.

**2. The `הפרופיל שלי` `PageHeader` is removed.** It repeated, word for word, the nav link that
opens this screen — present in `AppLayout`'s desktop nav and as the mobile top-bar profile icon,
whose `aria-label`/`title` carry that exact string. `ProfilePhoto` and the user's own name open the
screen instead. `.focused-page` already supplies block padding, so no CSS change was needed here;
measured top spacing after the change is 32 px with 0 px horizontal overflow at 1440 px and 390 px.

The equivalent title removals on `features/booking/MyOrdersPage` and
`features/dashboard/ProDashboardLayout` are recorded in those packages' own READMEs. Flow screens
deliberately keep their titles — they have a back button and no persistent nav, so the title *is*
the context — and `features/favorites`' `מועדפים` keeps its title because it has no desktop nav
entry at all.
