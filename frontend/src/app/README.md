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
- `router.tsx` — route tree. All routes render inside `AppLayout`; `/profile` and
  `/orders/:orderId` are nested under a bare `RequireAuth` (either role); `/issues/new`,
  `/issues/:issueId/booking`, and `/orders` are nested under `RequireAuth role="CUSTOMER"`;
  `/pro` and `/pro/availability` are nested under `RequireAuth role="PROFESSIONAL"` and,
  within that, under `features/dashboard`'s `ProDashboardLayout` (a shared two-tab shell).
- `AppLayout.tsx` — top nav shell (brand + login/register or profile/logout links
  depending on auth state). Frontend Milestone 3 added the first two real primary-nav
  destinations now that they exist as real screens: a customer's "ההזמנות שלי" (`/orders`)
  and a professional's own "לוח בקרה" (`/pro`) link. Favorites/a full mobile bottom nav
  (DESIGN_SYSTEM.md §50-51) still have no backing screen and are not added.
- `RequireAuth.tsx` — route guard. Redirects to `/login` when not authenticated (after
  the auth provider's initial rehydration finishes); supports an optional `role` prop to
  gate a route to one role.
- `HomePage.tsx` — placeholder home route (unchanged content since Milestone 0; only its
  wrapping element changed from `<main>` to `<div>` since `AppLayout` now owns the page's
  `<main>` landmark).
- `ProfilePage.tsx` — read-only display of `GET /api/users/me` + logout, behind
  `RequireAuth`. **As of the MS3/MS4 product-corrections pass (2026-08-17)**: also displays
  the customer's saved default address (`user.defaultAddress`, all 7 fields, when non-null)
  — `null` for a `PROFESSIONAL` caller or a pre-`V20` `CUSTOMER` with no recorded default,
  per that field's own "absent means no such object" convention. This was a live QA fix
  during this pass (the page previously did not render `defaultAddress` at all, even though
  the backend already returned it).
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

## Status
**Frontend Milestone 3 (2026-08-16, Standard booking flow) implemented.** `ProPlaceholderPage`
was removed — the professional's `/pro` route now renders a real dashboard
(`features/dashboard`'s `ProDashboardLayout` + `IncomingRequestsPage`/`AvailabilityPage`).
Router now also includes `/issues/:issueId/booking`, `/orders`, and `/orders/:orderId`
(`features/booking`). Further feature routes are added here incrementally as each
milestone lands (SOS routes in Milestone 4, notifications in Milestone 6, etc.).

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
