# shared/hooks

## Purpose
Reusable React hooks shared across features.

## Responsibilities
- Auth context/hook (current user, token, login/logout).
- Short-polling status hook (per `docs/architecture/overview.md` §3.3) used by the
  booking tracking screen and the professional's incoming-request feed — lands in
  Milestone 5.

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

## Status
`AuthProvider`/`useAuth` implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`). The status-polling hook lands in
**Milestone 5 — Notifications & real-time status**.
