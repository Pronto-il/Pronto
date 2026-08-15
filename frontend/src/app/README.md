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
- `router.tsx` — route tree. All routes render inside `AppLayout`; `/profile` and `/pro`
  are additionally nested under `RequireAuth`.
- `AppLayout.tsx` — minimal top nav shell (brand + login/register or profile/logout links
  depending on auth state). Deliberately does not build a real primary nav
  (home/bookings/favorites/profile) or a mobile bottom nav yet (DESIGN_SYSTEM.md §50-52) —
  those destinations don't exist as real screens until later milestones.
- `RequireAuth.tsx` — route guard. Redirects to `/login` when not authenticated (after
  the auth provider's initial rehydration finishes); supports an optional `role` prop to
  gate a route to one role.
- `HomePage.tsx` — placeholder home route (unchanged content since Milestone 0; only its
  wrapping element changed from `<main>` to `<div>` since `AppLayout` now owns the page's
  `<main>` landmark).
- `ProfilePage.tsx` — read-only display of `GET /api/users/me` + logout, behind
  `RequireAuth`.
- `ProPlaceholderPage.tsx` — minimal "בקרוב" landing spot for a professional after login,
  behind `RequireAuth`; the real dashboard is Milestone 6 scope.

## Status
**Milestone 1 (Auth & user management) implemented.** Router now includes the auth routes
(`/register`, `/register/customer`, `/register/professional`, `/verify`, `/login`) from
`features/auth`, plus the authenticated `/profile` and `/pro` placeholder routes. Further
feature routes are added here incrementally as each milestone lands (issue routes in
Milestone 2, booking routes in Milestone 3/4, etc.).
