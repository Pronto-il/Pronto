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
- `App.tsx` — wraps `RouterProvider` in `AuthProvider` (from `shared/hooks`).
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
  `RequireAuth`.

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
