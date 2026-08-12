# `bookings`

## Purpose

`Orders` — Standard + SOS booking flows, accept/reject, and status transitions.

## Responsibilities

- Owns the `Order` JPA entity mapped to the `orders` table.
- Standard path: professional listing (filtered by issue category), price offers, pick a
  professional, accept/reject, confirmation, status tracking.
- SOS path: currently-available professional listing (via `availability`), urgent
  request, accept/reject, fallback messaging.
- Status transitions: `PENDING -> CONFIRMED -> ON_THE_WAY -> COMPLETED`, plus
  `CANCELLED` (used for both rejection and cancellation, disambiguated by
  `cancelled_by`) and `EXPIRED`.

## Key classes

None yet — stub package (`package-info.java` only).

## Interactions with other packages

- Depends on `issues` (an order is created against a persisted, confirmed issue),
  `professionals` and `availability` (matching/listing), `users` (customer FK).
- Triggers `notifications` on every status transition (email + in-app).

## Data model

Owns the `orders` table (see `docs/architecture/data-model.md` §2.8). Note: no distinct
`'REJECTED'` status exists — a professional's rejection is represented as
`order_status = 'CANCELLED'` with `cancelled_by = 'PROFESSIONAL'`.

## Status

Stub only, no logic yet — implemented across **Milestone 3 (Standard booking flow)** and
**Milestone 4 (SOS booking flow)** per `docs/architecture/implementation-plan.md`.
