# `users`

## Purpose

Shared `User` entity/profile logic used by both customer and professional roles.

## Responsibilities

- Owns the `User` JPA entity mapped to the `users` table.
- Shared profile fields (full name, email, role) common to both account types.
- Soft-delete / account-deletion support (`deleted_at`) consumed by `auth`.

## Key classes

None yet — stub package (`package-info.java` only). The `User` entity and any
repository/service will live here once Milestone 1 starts.

## Interactions with other packages

- `auth` depends on `users` for authentication (reads/writes `users` rows, plus its own
  `verification_codes` table).
- `professionals` depends on `users` — a professional profile is a 1:1 extension of a
  `users` row with `role = 'PROFESSIONAL'`.
- `issues`, `bookings`, `notifications` all reference `users.id` as a foreign key
  (customer, related party) without depending on `users` package internals beyond the ID.

## Data model

Owns the `users` table (see `docs/architecture/data-model.md` §2.2).

## Status

Stub only, no logic yet — implemented in **Milestone 1 (Auth & user management)** per
`docs/architecture/implementation-plan.md`.
