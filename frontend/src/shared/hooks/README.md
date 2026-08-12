# shared/hooks

## Purpose
Reusable React hooks shared across features.

## Responsibilities
- Auth context/hook (current user, token, login/logout) — needed once login exists.
- Short-polling status hook (per `docs/architecture/overview.md` §3.3) used by the
  booking tracking screen and the professional's incoming-request feed.

## Status
Stub only — no hooks yet. Auth context lands in **Milestone 1 — Auth & user
management**; the status-polling hook lands in **Milestone 5 — Notifications & real-time
status** (`docs/architecture/implementation-plan.md`).
