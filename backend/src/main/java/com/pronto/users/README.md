# `users`

## Purpose

Shared `User` JPA entity/profile plus the self-service `/api/users/me` endpoints (get own
profile, update own profile — `CUSTOMER` only, delete own account). Implements
`docs/architecture/api-contract.md` §2.4–2.6.

## Responsibilities

- Owns the `User` JPA entity mapped to the `users` table (`entity.User`,
  `entity.UserRole`), matching `V2__create_users.sql` exactly (`ddl-auto: validate`).
- `GET /api/users/me` — returns the caller's own profile; for a `PROFESSIONAL` caller,
  includes a nested `professional` object (category/service area/base price) sourced from
  the `professionals` package. **As of the MS3/MS4 product-corrections pass**: for a
  `CUSTOMER` caller with a saved default address (`users.default_city` non-null), also
  includes a nested `defaultAddress` object (`DefaultAddressInfo`: city/street/houseNumber/
  apartment/floor/entrance/addressNotes) — `null` for a `PROFESSIONAL` caller or a
  pre-`V20` `CUSTOMER` with no recorded default address, mirroring `professional`'s own
  "absent means no such object" convention exactly. No new endpoint was added to *update*
  the default address — this is a read-only mirror of the `users.default_*` columns
  `auth`'s registration flow already populates (`V20`). **As of the professional weekly
  availability calendar design's M2 (2026-08-18)**: also includes a top-level `phone` field
  (not nested), mirroring `defaultAddress`'s exact nullability/placement convention — `null`
  for a `PROFESSIONAL` caller, `null` for a `CUSTOMER` with no recorded phone (a pre-`V28`
  account). Set once at registration via the new `customer.phone` field (`auth` package,
  `V28`'s `users.phone` column). **As of the MS10 profile redesign (2026-08-19)**: the
  nested `professional` object also gained `profileImageUrl` (`null` if no photo, else a
  presigned URL — resolved the same way `professionals.dto.ProfessionalProfileResponse
  .profileImageUrl` already is, via `StorageService#getPresignedUrl`), and both
  `defaultAddress`/`phone` are **no longer read-only** — see `PUT /api/users/me` below.
- `PUT /api/users/me` — new, MS10 profile redesign (2026-08-19). `CUSTOMER` only (route-level
  gate, `config.UsersWebConfig`, plus a defense-in-depth service-layer re-check). Updates
  `fullName`/`phone`/all 7 `default_*` columns via `UpdateUserMeRequest` (a locally-defined
  DTO — its nested address sub-record deliberately mirrors `auth.dto.DefaultAddressRequest`'s
  shape/validation independently rather than reusing it directly, avoiding a new
  `users -> auth` package dependency edge, same pattern `dto.DefaultAddressInfo` already
  established). Load-mutate-save on a single-owner row (no migration; every setter already
  existed). Returns the same `UserMeResponse` shape `GET /api/users/me` does. This reverses
  the previous "read-only, no endpoint" framing for `defaultAddress`/`phone` — deliberate,
  since `orders.service_*` is captured as its own snapshot at order-creation time, decoupled
  from `users.default_*`, so editing a saved default address has no correctness impact on any
  existing/in-flight order. `email` stays read-only on purpose (changing it would need to
  re-trigger email verification, out of scope here). See
  `docs/architecture/product-ms10-profile-redesign-design.md` §3.2/§4.
  **As of the address-flow redesign**: `defaultAddress` is **optional** on this request, and
  omitting it leaves the saved address exactly as it was (there is no "clear my address" here,
  and there never was). It had to become optional because customer registration stopped
  collecting an address — a customer may legitimately have none, and requiring one here would
  mean such a customer could not correct a typo in their own name without first inventing a home
  address. A *supplied* address is unchanged: required in full, and must carry a selected place.
- `PUT /api/users/me/default-address` — new, address-flow redesign. `CUSTOMER` only, same
  route-level gate plus service-layer re-check. The home address on its own
  (`dto.CustomerAddressRequest` as the whole body), with identical rules to the address half of
  `PUT /api/users/me`. Exists for the booking flow's "הפוך את זה לכתובת הבית", which has an
  address and nothing else: routing it through `PUT /api/users/me` would mean resending the
  customer's name and phone from client-side state to save something unrelated to either — and
  `phone` is not inert, since a value that comes back changed costs the customer their phone
  verification. Deliberately **not** behind `UsersWebConfig`'s rate limiter; that limiter exists
  because `PUT /api/users/me` is a `DUPLICATE_PHONE` enumeration oracle, and this endpoint takes
  no phone number.
- `DELETE /api/users/me` — soft-delete + PII anonymization (`deleted_at`, `full_name`,
  `email` per the exact rule in `api-contract.md` §2.5). Does **not** touch
  `professionals`/`issues`/`orders` rows — flagged there as a dependency later
  milestones' listing queries need to account for.

## Key classes

| Class | Role |
|---|---|
| `entity.User` | JPA entity for `users`. |
| `entity.UserRole` | `CUSTOMER` \| `PROFESSIONAL` \| `ADMIN` enum, `@Enumerated(STRING)`. `ADMIN` is new as of Production Roadmap MS1 — see the MS1 paragraph under Status. |
| `repository.UserRepository` | `findByEmailIgnoreCase`, `existsByEmailIgnoreCase` (case-insensitive, matches `ux_users_email_lower`). |
| `dto.UserMeResponse` / `dto.ProfessionalInfo` / `dto.DefaultAddressInfo` | `GET`/`PUT /api/users/me` response shape. `DefaultAddressInfo` is new as of the MS3/MS4 product-corrections pass. `UserMeResponse` gained a top-level `phone` field as of the professional weekly availability calendar design's M2 — no new nested DTO needed (unlike `defaultAddress`, `phone` is a plain scalar, read directly off `User.getPhone()`). `ProfessionalInfo` gained `profileImageUrl` as of MS10. |
| `dto.UpdateUserMeRequest` | New, MS10. Request DTO for `PUT /api/users/me` — `fullName`/`phone`/`defaultAddress`. As of the address-flow redesign `defaultAddress` is optional (omitted → the saved address is left alone) and its type was extracted to the top-level `dto.CustomerAddressRequest`, which the new address-only endpoint takes as its whole body. |
| `dto.CustomerAddressRequest` | New, address-flow redesign. A customer's home address, shared by `PUT /api/users/me` (nested) and `PUT /api/users/me/default-address` (the whole body). Locally defined rather than reusing `auth.dto.DefaultAddressRequest`, avoiding a `users -> auth` dependency edge — the same convention `dto.DefaultAddressInfo` already established. `houseNumber` is digits-only (`maps.HouseNumbers`). |
| `config.UsersWebConfig` | New, MS10. Registers a `RoleRequiredInterceptor` scoped to `PUT` only on `/api/users/me` and (address-flow redesign) `/api/users/me/default-address` (`CUSTOMER`-only) — mirrors `reviews.config.ReviewsWebConfig`'s same-path/different-HTTP-method-gate precedent. `GET`/`DELETE` on the same path stay ungated. |
| `service.UsersService` | `getMe`/`updateMe`/`updateDefaultAddress`/`deleteMe` business logic. The two write paths share one private `applyDefaultAddress`, so "invalidate the previous resolution, then adopt the selection" is written once. |
| `controller.UsersController` | `/api/users/me` GET/PUT/DELETE, `/api/users/me/default-address` PUT. |

All three endpoints require a valid JWT — enforced entirely by `auth`'s `SecurityConfig` +
`JwtAuthenticationFilter`; this package's controller has no auth logic of its own beyond
reading `@AuthenticationPrincipal AuthenticatedUser` (a `common` type). `PUT`'s role gate is
this package's own `config.UsersWebConfig`, not `auth`.

## Interactions with other packages

- `auth` depends on this package for `User`/`UserRepository` (registration, login,
  per-request JWT validation).
- Depends on `professionals` (`ProfessionalRepository`) to populate the nested
  `professional` object in `GET /api/users/me` for professional callers.
- **As of MS10**: depends on `storage` (`StorageService#getPresignedUrl`) to resolve
  `professional.profileImageUrl` — same collaborator `professionals.service
  .ProfessionalsService` already depends on for its own photo resolution.
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`) and the
  `AuthenticatedUser` principal resolved from the JWT.
- `issues`, `bookings`, `notifications` (later milestones) are expected to reference
  `users.id` as a foreign key without depending on this package's internals beyond the ID.

## Data model

Owns the `users` table (`docs/architecture/data-model.md` §2.2). **As of the professional
weekly availability calendar design's M2 (2026-08-18)**: gained one new column, `phone
VARCHAR(20)` (`V28__alter_users_add_phone.sql`), nullable at the DB level, same
requiredness/nullability split as the `default_*` columns above.

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

**MS3/MS4 product-corrections pass (2026-08-17)**: `GET /api/users/me` gained the nested
`defaultAddress` object described above (`UsersService.getMe`, `dto.DefaultAddressInfo`).
Backend-only, response-shape addition — no migration (the `users.default_*` columns already
existed, from `V20`), no controller change. Consumed by the frontend's
`AddressSelectionStep` (`features/booking`) and displayed on `ProfilePage`. Full design
record: `docs/architecture/ms3-ms4-corrections-design.md` §1.

**Professional weekly availability calendar, M2 (2026-08-18)**: `GET /api/users/me` gained
the top-level `phone` field described above (`UsersService.getMe`, `dto.UserMeResponse`).
Backend-only, response-shape/migration addition — `V28` adds `users.phone`, `auth`'s
registration flow now requires `customer.phone` and persists it via `User.setPhone(...)`, no
new endpoint. Manually verified end-to-end against a real running backend + Postgres: a
`CUSTOMER` registration with `phone` persisted it, `GET /api/users/me` returned it correctly;
a `PROFESSIONAL` registration left `users.phone = NULL`. See
`docs/architecture/professional-weekly-calendar-design.md` §9.1 and
`backend/.../bookings/README.md`'s M2 section for the order-visibility half of this same
feature.

**MS10 — Profile UI Redesign (2026-08-19)**: added `PUT /api/users/me`
(`dto.UpdateUserMeRequest`, `config.UsersWebConfig`, `UsersService#updateMe`) and one new
`ProfessionalInfo.profileImageUrl` field, both described above. Unit-tested
(`UsersServiceTest`): the `CUSTOMER`-only update happy path, the `PROFESSIONAL`-caller
defense-in-depth `403 FORBIDDEN` (and that it doesn't touch the `users` row at all), the
deleted-user `401 UNAUTHORIZED` path, and both branches of the new `profileImageUrl`
resolution (present/absent `profileImageKey`). No new migration, no new `ErrorCode`. Full
design record: `docs/architecture/product-ms10-profile-redesign-design.md`.

**Production Roadmap MS1 — a third role (2026-08-22).** `entity.UserRole` gained `ADMIN`, the
Pronto operator, and `V40__alter_professionals_approval_lifecycle.sql` widened `ck_users_role`
to `('CUSTOMER','PROFESSIONAL','ADMIN')` to match — the enum mirrors that constraint, which is
why the two had to move together. This package owns the constant and nothing else about the
role: the only surface an `ADMIN` can reach is `/api/admin/professionals/**`, gated by
`professionals.config.ProfessionalsWebConfig` (MS7 owns the wider operations surface).
**`ADMIN` is not self-registerable, by explicit guard, not by accident of parsing**:
`auth.dto.RegisterRequest.role` is typed with this enum, so the mere existence of the constant
would otherwise have made an administrator creatable by anyone who can reach the public
registration endpoint — `auth.service.AuthService#register` rejects `role = ADMIN` with a
`400 VALIDATION_ERROR` before any row is written, and an `ADMIN` row is created only by a
deliberate operational step (for which this repository currently documents no procedure — a
recorded MS1 known limitation, owned by MS7). Every role-branching service treats an `ADMIN`
caller as **neither** a customer nor a professional: they have no orders, issues, favorites,
SOS requests or professional profile, so `bookings.service.BookingsService#listMyOrders` and
`sos.service.SosService#listMine` had their bare `else` branches replaced with explicit
three-way branches that throw `403 FORBIDDEN` rather than resolving an operator into one of the
other two roles. No change to `entity.User`, `UserRepository`, the `/api/users/me` endpoints or
this package's DTOs. See `docs/production-roadmap/reports/MS1-report.md` and
`professionals/README.md`.

---

## Production MS1 (2026-08-25) — phone becomes an identity

### `User`

`phone` stopped being customer contact detail and became the account's second identity: canonical
E.164, unique, required at the API layer for every new registration of every role, and usable as a
login identifier once `phoneVerified`. New `phoneVerified` column mirrors `emailVerified`, including
its `false` default — no pre-existing row is grandfathered. `isFullyVerified()` is the single
question every gate asks.

`setPasswordHash` was added for password recovery. It deliberately takes an already-encoded value:
this entity does not know what a `PasswordEncoder` is, and a setter taking plaintext would be one
refactor away from storing one.

### `UsersService`

- `getMe` returns `phone` for **every** role now. Blanking it for a `PROFESSIONAL` was right while
  this was contact detail and wrong once it is the identity they sign in with.
- `updateMe` no longer assigns `phone` directly. `applyPhoneChange` normalizes, checks uniqueness,
  and **drops `phoneVerified` when the number changes** — without that last rule this endpoint would
  be a complete bypass of phone verification. A no-op edit (the same canonical number resubmitted
  with the rest of the profile) leaves the flag alone, so saving the profile form does not cost a
  user their verification.
- `deleteMe` now nulls the phone as well as rewriting the email. `ux_users_phone` is a total unique
  index, so leaving the number on a tombstone would reserve it forever and stop its real owner from
  ever registering — the identical problem the email rewrite already solved.

### `ContactVerificationGuard` (new)

The backend half of the legacy-account policy. Accounts created before MS1 authenticate normally
(email + password + email OTP proves the one channel they did verify) but are refused
`PHONE_VERIFICATION_REQUIRED` at issue creation, order creation and SOS activation — the operations
that end with a professional at somebody's front door.

Deliberately **not** used for the professional side: a professional's phone verification is folded
into `ProfessionalEligibility.ELIGIBLE_JPQL` instead, so an unverified professional is simply not
discoverable across all six gated paths rather than being checked in six places, one of which a
seventh consumer would forget.

The frontend routes a `PHONE_VERIFICATION_REQUIRED` response to the phone-capture screen. That is
convenience; the rule holds against a direct API call with a perfectly valid JWT.

### Related documentation

`docs/production-roadmap/reports/prod-MS1-report.md` · `docs/architecture/data-model.md`
"Production MS1" section · `V46`, `V48`.

## `GET /api/users/me` — `phoneVerificationRequired`

Added with the OTP master switch (`OTP_VERIFICATION_ENABLED`, see the `auth` README).
`UsersService#getMe` reports it straight from `auth.config.VerificationPolicy`.

It exists because `phoneVerified: false` describes two states a client has to treat differently —
**unproved and being asked**, and **unproved and nobody is asking** — and the phone-capture screen
was reading the first meaning into both. With verification switched off it would have offered to send
a code that `AuthService#capturePhone` now refuses to dispatch.

`emailVerified` and `phoneVerified` still report the stored columns and are never adjusted by policy.
Making `phoneVerified` report `true` under the relaxed policy would have removed the need for this
field and corrupted the only record of who still owes a verification — the record that decides who
gets asked when the requirement is turned back on.
