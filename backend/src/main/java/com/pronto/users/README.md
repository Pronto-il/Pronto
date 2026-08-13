# `users`

## Purpose

Shared `User` JPA entity/profile plus the self-service `/api/users/me` endpoints (get own
profile, delete own account). Implements `docs/architecture/api-contract.md` §2.4–2.5.

## Responsibilities

- Owns the `User` JPA entity mapped to the `users` table (`entity.User`,
  `entity.UserRole`), matching `V2__create_users.sql` exactly (`ddl-auto: validate`).
- `GET /api/users/me` — returns the caller's own profile; for a `PROFESSIONAL` caller,
  includes a nested `professional` object (category/service area/base price) sourced from
  the `professionals` package.
- `DELETE /api/users/me` — soft-delete + PII anonymization (`deleted_at`, `full_name`,
  `email` per the exact rule in `api-contract.md` §2.5). Does **not** touch
  `professionals`/`issues`/`orders` rows — flagged there as a dependency later
  milestones' listing queries need to account for.

## Key classes

| Class | Role |
|---|---|
| `entity.User` | JPA entity for `users`. |
| `entity.UserRole` | `CUSTOMER` \| `PROFESSIONAL` enum, `@Enumerated(STRING)`. |
| `repository.UserRepository` | `findByEmailIgnoreCase`, `existsByEmailIgnoreCase` (case-insensitive, matches `ux_users_email_lower`). |
| `dto.UserMeResponse` / `dto.ProfessionalInfo` | `GET /api/users/me` response shape. |
| `service.UsersService` | `getMe`/`deleteMe` business logic. |
| `controller.UsersController` | `/api/users/me` GET/DELETE. |

Both endpoints require a valid JWT — enforced entirely by `auth`'s `SecurityConfig` +
`JwtAuthenticationFilter`; this package's controller has no auth logic of its own beyond
reading `@AuthenticationPrincipal AuthenticatedUser` (a `common` type).

## Interactions with other packages

- `auth` depends on this package for `User`/`UserRepository` (registration, login,
  per-request JWT validation).
- Depends on `professionals` (`ProfessionalRepository`) to populate the nested
  `professional` object in `GET /api/users/me` for professional callers.
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`) and the
  `AuthenticatedUser` principal resolved from the JWT.
- `issues`, `bookings`, `notifications` (later milestones) are expected to reference
  `users.id` as a foreign key without depending on this package's internals beyond the ID.

## Data model

Owns the `users` table (`docs/architecture/data-model.md` §2.2).

## Assumptions / judgment calls made during implementation

- `User.failedLoginAttempts` is mapped as Java `short` (not `int`/`Integer`) specifically
  to match `failed_login_attempts SMALLINT` under Hibernate's strict `ddl-auto: validate`
  column-type check.
- `UsersService.loadActiveUser` re-checks `deleted_at IS NULL` at the service layer even
  though `auth`'s JWT filter already excludes soft-deleted users from authenticating —
  a small defensive re-check against the race window between token validation and handler
  execution (e.g. a concurrent delete), not redundant paranoia beyond that.

## Status

Implemented in **Milestone 1 (Auth & user management)**, per
`docs/architecture/implementation-plan.md`. QA-validated (2026-08-13, two passes — full
validation, then a re-verification after the lockout bug documented in `auth/README.md`
was fixed) against a real Postgres instance: `GET`/`DELETE /api/users/me` both exercised
for customer and professional callers, including the nested `professional` object and the
soft-delete + PII anonymization behavior.
