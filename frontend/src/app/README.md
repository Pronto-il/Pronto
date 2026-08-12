# app

## Purpose
Routing, layout, and root application configuration — the composition root that wires
feature modules together into the single-page app described in
`docs/architecture/overview.md` §3.1.

## Responsibilities
- Router setup (`react-router-dom`), including route-based role gating between the
  customer and professional experiences once auth exists.
- App-level layout (shared chrome, if any) wrapping feature routes.
- Root configuration mounted from `src/main.tsx`.

## Status
Minimal real content as of Milestone 0 — Foundation
(`docs/architecture/implementation-plan.md`): `router.tsx` defines a single placeholder
home route (`/`) rendering `HomePage.tsx`, and `App.tsx` wires it in via
`RouterProvider`. Feature routes are added here incrementally as each milestone lands
(auth routes in Milestone 1, issue routes in Milestone 2, etc.).
