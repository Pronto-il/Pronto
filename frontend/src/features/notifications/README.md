# features/notifications

## Purpose
In-app notification display, consuming the status-polling hook from `shared/hooks`.

## Responsibilities
- Notification list/badge UI (booking status changes, incoming requests, etc.).
- Consumes the short-polling status hook (`shared/hooks`) rather than implementing its
  own polling — this feature is presentation only.

## Status
Stub only — no components yet. Implemented in **Milestone 5 — Notifications & real-time
status** (`docs/architecture/implementation-plan.md`), against the backend
`notifications` package and its short-polling endpoints.
