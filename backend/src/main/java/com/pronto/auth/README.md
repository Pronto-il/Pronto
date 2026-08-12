# `auth`

## Purpose

Registration, login, email verification codes, password hashing, account lockout, and
token issuance for both Customer and Professional accounts.

## Responsibilities

- Account registration (email + password) and email verification code delivery/consumption.
- Login, password hashing (bcrypt or equivalent — never plaintext).
- Account lockout after 5 failed login attempts (`users.failed_login_attempts` /
  `users.locked_until`).
- Issuing a stateless auth token (JWT or equivalent) for subsequent requests.
- Account deletion endpoint (soft delete via `users.deleted_at`).

## Key classes

None yet — stub package (`package-info.java` only). No entities, services, or
controllers exist as of Milestone 0.

## Interactions with other packages

- Depends on `users` for the `User` entity/profile it authenticates against.
- Owns the `verification_codes` table exclusively.
- `professionals` depends on a verified `users` row existing before a professional
  profile can be created, but does not depend on `auth` directly.

## Data model

Reads/writes `users` (see `docs/architecture/data-model.md` §2.2) and
`verification_codes` (§2.3).

## Status

Stub only, no logic yet — implemented in **Milestone 1 (Auth & user management)** per
`docs/architecture/implementation-plan.md`.
