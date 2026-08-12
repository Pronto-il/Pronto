# `professionals`

## Purpose

Professional profile, service area, and reliability score. No approval workflow — v1.0
auto-approves professional accounts (`approval_status` defaults to and stays
`'APPROVED'`).

## Responsibilities

- Owns the `Professional` JPA entity mapped to the `professionals` table (category,
  service area, standing price offer, reliability score).
- Professional profile creation/edit (dashboard use, Milestone 6) and lookups used by the
  Standard/SOS booking listings (Milestones 3-4).

## Key classes

None yet — stub package (`package-info.java` only).

## Interactions with other packages

- Depends on `users` (1:1 extension via `user_id`) and `categories`/`issues` package
  concerns via `category_id` (a professional offers exactly one category in v1.0 — see
  `docs/architecture/data-model.md` §3 item 2).
- Consumed by `bookings` for professional listings/matching, and by `availability` for
  a professional's slot calendar.

## Data model

Owns the `professionals` table (see `docs/architecture/data-model.md` §2.4). Note the
v1.0 simplification: one category per professional profile, `approval_status` column kept
but functionally inert.

## Status

Stub only, no logic yet — implemented in **Milestone 1 (Auth & user management)** per
`docs/architecture/implementation-plan.md` (profile only in Milestone 1; dashboard/edit
flows in Milestone 6).
