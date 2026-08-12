# `notifications`

## Purpose

Notification records and status polling endpoints, plus email dispatch.

## Responsibilities

- Owns the `Notification` JPA entity mapped to the `notifications` table.
- Backs the short-polling real-time status updates (client polls every 3-5s) described
  in `docs/architecture/overview.md` §3.3 — in-app channel.
- Dispatches email notifications (verification codes, order status changes) — email
  channel.
- Read/unread tracking (`read_at`) for the in-app feed.

## Key classes

None yet — stub package (`package-info.java` only).

## Interactions with other packages

- Written to by `bookings` on every order status transition, and by `auth` for
  verification codes.
- Read by the polling endpoints consumed by the frontend's tracking screen and
  professional incoming-requests feed.

## Data model

Owns the `notifications` table (see `docs/architecture/data-model.md` §2.9).

## Status

Stub only, no logic yet — implemented in **Milestone 5 (Notifications & real-time
status)** per `docs/architecture/implementation-plan.md`.
