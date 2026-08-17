# shared/hooks

## Purpose
Reusable React hooks shared across features.

## Responsibilities
- Auth context/hook (current user, token, login/logout).
- Short-polling status hook (per `docs/architecture/overview.md` §3.3) used by the
  booking tracking screen and the professional's incoming-request feed — shipped in
  Frontend Milestone 3 (2026-08-16).

## Structure
- `authContext.ts` — the `AuthContext` (React context) + `AuthContextValue` type. Kept
  separate from the provider component so the file only exports non-component values
  (avoids the React Fast Refresh / `only-export-components` lint warning).
- `AuthProvider.tsx` — the context provider. Holds `token`/`user`/`isLoading`; persists
  the token to `localStorage` (`pronto_auth_token`); rehydrates on app load by calling
  `GET /api/users/me` (a 401 during rehydration just clears the token — no forced
  redirect from here). Registers the token-getter `shared/api/httpClient.ts` uses to
  attach the `Authorization` header. `login(email, password)` calls
  `POST /api/auth/login`, stores the token, then fetches the full `GET /api/users/me`
  profile so the context always holds one consistent `UserMeResponse` shape.
  `logout()` is a client-side-only discard (no server-side logout endpoint in v1.0).
- `useAuth.ts` — the `useAuth()` hook, throws if used outside `AuthProvider`.
- `usePolling.ts` — the generic short-polling hook: fetches immediately on mount, then
  re-fetches every `intervalMs` (default 4000ms). Skips a tick if the previous request is
  still in flight (never overlaps requests), cleans up its interval on unmount, and is a
  no-op while `enabled` is `false`. Backing implementation for `useOrderStatus` and any
  other future polling need, per `docs/architecture/overview.md` §3.3 (short-polling, not
  WebSocket).
- `useOrderStatus.ts` — order-tracking-screen polling wrapper around `usePolling`, built
  on `shared/api/bookings.ts`'s `getOrder` (`GET /api/bookings/orders/{orderId}`). Stops
  polling once the last-observed `orderStatus` reaches a terminal state (`COMPLETED`/
  `CANCELLED`/`REJECTED`/`EXPIRED`) — no point short-polling a status that can never
  change again. Consumed by `features/booking/OrderTrackingPage.tsx`; the professional's
  incoming-request feed (`features/dashboard/IncomingRequestsPage.tsx`) consumes
  `usePolling` directly instead (it polls a list endpoint, not a single order).

## Status
`AuthProvider`/`useAuth` implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`). `usePolling`/`useOrderStatus` shipped in
**Frontend Milestone 3 — Standard booking flow (2026-08-16)**, not Milestone 5 as this
doc previously (incorrectly) said — Milestone 5's backend work (the `notifications`
package's email-dispatch and order-expiry scheduler jobs) is unrelated server-side
scheduling, already complete separately, and never blocked this hook's frontend delivery.
