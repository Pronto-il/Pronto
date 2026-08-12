# features/auth

## Purpose
Customer and professional registration, email verification, and login screens.

## Responsibilities
- Registration forms for both user types (customer, professional).
- Email verification code entry/resend flow.
- Login form and session/token handling on the client side.
- Surfacing account-lockout state after repeated failed logins (backend enforces the
  5-attempt lockout; this feature displays the resulting error state).

## Status
Stub only — no screens yet. Implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`), against the `auth`/`users`/`professionals`
backend packages.
