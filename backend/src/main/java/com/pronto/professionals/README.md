# `professionals`

## Purpose

Professional profile (category, service area, standing price offer, reliability score).
No approval workflow — v1.0 auto-approves professional accounts (`approval_status`
defaults to and stays `'APPROVED'`).

## Responsibilities

- Owns the `Professional` JPA entity mapped to the `professionals` table, matching
  `V4__create_professionals.sql` exactly (`ddl-auto: validate`).
- Provides `ProfessionalRepository` (used by `auth` at registration time, and by `users`
  to populate `GET /api/users/me`'s nested `professional` object).
- Provides a **read-only** `Category` entity + `CategoryRepository`, mapped to the
  `categories` table, used solely to validate `RegisterRequest.categoryId` at professional
  registration time (`api-contract.md` §2.1). The application never writes to
  `categories` — only `V10__seed_categories.sql` does.

## Key classes

| Class | Role |
|---|---|
| `entity.Professional` | JPA entity for `professionals`. `userId`/`categoryId` are plain FK columns (not `@ManyToOne`/`@OneToOne` associations) — this package never needs to navigate the related `User`/`Category` object graph, just the id. |
| `entity.Category` | Read-only reference entity for `categories` (8 fixed rows). |
| `repository.ProfessionalRepository` | `findByUserId`. |
| `repository.CategoryRepository` | Plain `JpaRepository`; used via `existsById` only in this milestone. |

No service/controller layer yet — Milestone 1 only needs entity creation (via `auth`) and
read access (via `users`/`auth`). Profile edit/dashboard flows are Milestone 6's scope.

## Interactions with other packages

- Depended on by `auth` (`AuthService`) to create a `Professional` row at professional
  registration and to validate `categoryId` via `CategoryRepository`.
- Depended on by `users` (`UsersService`) to populate `GET /api/users/me`'s nested
  `professional` object.
- **Milestone 2**: depended on by `issues` (`IssuesService`, to validate `categoryId` on
  `POST /api/issues`) and by `ai` (`ClassificationService`, to resolve an AI-returned
  category code to a `categories` row and to build the OpenAI prompt's category list) —
  both reuse `CategoryRepository` as-is. See "Assumptions" below: this settles the
  Milestone-1-flagged "should `Category` move to a shared package" question.
- Will be consumed by `bookings` (Standard/SOS listings) and `availability` in later
  milestones.

## Data model

Owns the `professionals` table (`docs/architecture/data-model.md` §2.4). Also maps
`categories` (§2.1) as a read-only reference entity — see the placement note below.

## Assumptions / judgment calls made during implementation

- **`Category` entity placement (flagged in Milestone 1, resolved in Milestone 2).** No
  dedicated `categories` package exists. `Category`/`CategoryRepository` were added here
  in Milestone 1 purely so professional registration could validate `categoryId` against a
  real FK target, with a flagged open question of whether `issues` (which would also need
  `category_id` in Milestone 2) should get its own copy or a shared package should be
  introduced. **Resolved during Milestone 2's implementation**: `issues` and `ai` both
  depend on this package's `CategoryRepository` directly (read-only), the same pattern
  `auth` already used — no duplication, no new package. Still worth revisiting if a future
  milestone gives `categories` real write/management behavior (e.g. an admin CRUD screen),
  at which point a dedicated package would earn its keep; not needed for read-only lookups.
- `Professional.approvalStatus` is a plain `String` (not a Java enum) — matches the
  DB `CHECK` constraint's value set exactly and the column is functionally inert in v1.0
  (always `'APPROVED'`), so an enum felt like unnecessary ceremony for a value that's
  never branched on by application logic yet.
- `reliabilityScore` is mapped but never set/read by any Milestone 1 code path — no
  rating/review mechanism exists yet (per `data-model.md` §4's open question).

## Status

Entity/repository layer implemented in **Milestone 1 (Auth & user management)**, per
`docs/architecture/implementation-plan.md` (profile creation only, via `auth`'s
registration flow; dashboard/edit flows are Milestone 6). QA-validated (2026-08-13, two
passes) against a real Postgres instance: professional registration creates a
`professionals` row with `approval_status = 'APPROVED'` and no admin gate, `categoryId`
validation against the seeded `categories` table (including the invalid-`categoryId`
edge case), and the nested `professional` object surfaced correctly via
`GET /api/users/me`.

**Not yet fixed, flagged (see `docs/architecture/data-model.md` §4 and
`docs/architecture/overview.md` §6):** the already-applied `V5__create_availability_slots.sql`
migration still implements the single-table SOS design that `data-model.md` §2.6/§3 item 5
rejected — no dedicated `sos_availability` table exists yet. This package's registration
flow already accounts for that gap (see "Does not model `sos_availability`" in
`entity/Professional`'s Javadoc) rather than silently working around it.
