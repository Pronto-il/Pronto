# Pronto — REST API Contract: Milestone 1 (Auth & User Management)

Status: **design pass for Milestone 1, ready for `pronto-coding`**. Written by
`pronto-planning`. Builds on top of the already-applied Milestone 0 schema
(`backend/src/main/resources/db/migration/V1`–`V10`, do not alter) and
`docs/architecture/data-model.md` §2.2–§2.4. Scope is auth + self-service profile only —
no issues/bookings/availability endpoints here (those belong to later milestones' own
contract docs).

This doc is a **precise contract spec** (request/response JSON shapes, status codes, error
codes, field-level validation), not literal Java code — writing the controllers/services is
`pronto-coding`'s job.

Cross-referenced from `docs/architecture/overview.md` §3.7 and §4 (`auth` package row).

---

## 0. Conventions used throughout this doc

| Convention | Choice |
|---|---|
| Base paths | `/api/auth/*` for authentication actions (register/verify/login); `/api/users/me` for the authenticated caller's own profile (get/delete). Chosen so "do a thing to log in/out/verify" and "manage my own resource" are clearly separated, consistent with typical REST practice. |
| Request/response bodies | JSON, `camelCase` field names (translated at the JPA/DTO boundary from the DB's `snake_case` columns — ordinary Spring/Jackson default, not a special decision). |
| Auth header | `Authorization: Bearer <jwt>` on every endpoint marked "auth required" below. |
| Timestamps in JSON | ISO-8601 / RFC 3339 with offset, e.g. `"2026-08-13T12:34:56Z"` (Jackson default for `Instant`/`OffsetDateTime`). |
| Money fields | JSON number with up to 2 decimal places, mapping to `NUMERIC(10,2)`. |
| Language of error messages | English in the API response body. **Not** Hebrew — this is a backend contract; Hebrew-language presentation is the frontend's responsibility (translate `error.code` client-side), consistent with "no frontend/UI concerns" being out of this doc's scope. |

---

## 1. Standard error response envelope

Used consistently by every endpoint in this doc (and intended as the pattern for all
future milestones' endpoints too — worth `pronto-coding` implementing once as a global
`@ControllerAdvice`/`@ExceptionHandler` in the `common` package rather than per-controller).

```json
{
  "timestamp": "2026-08-13T12:34:56Z",
  "path": "/api/auth/login",
  "error": {
    "code": "ACCOUNT_LOCKED",
    "message": "Account is temporarily locked due to too many failed login attempts. Try again later.",
    "details": null
  }
}
```

- `error.code` — stable machine-readable taxonomy (below), for the frontend to branch on;
  never changes across releases without a version bump.
- `error.message` — human-readable (English), for logs/debugging; not guaranteed to be
  shown verbatim to end users (frontend maps `code` → Hebrew copy).
- `error.details` — nullable; shape depends on the error (validation errors carry a
  field-error array, lockout carries retry-time info — see per-endpoint sections).

### Error code taxonomy (Milestone 1 scope)

| `error.code` | HTTP status | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Request body failed field-level validation. `details`: `[{ "field": "email", "message": "must be a valid email address" }, ...]`. |
| `DUPLICATE_EMAIL` | 409 | Registration email already in use (case-insensitive, per `ux_users_email_lower`). |
| `INVALID_CODE` | 400 | Verification code doesn't match any active code for the user (wrong code, or no code exists). |
| `CODE_EXPIRED` | 410 | Verification code matched but `expires_at` has passed. |
| `CODE_ALREADY_CONSUMED` | 409 | The matched code's `consumed_at` is already set (already used). **Note**: structurally unreachable in practice given `/verify`'s check order — see the note under §2.2. |
| `EMAIL_ALREADY_VERIFIED` | 409 | `/verify` called for a user whose `email_verified` is already `true`. |
| `INVALID_CREDENTIALS` | 401 | Login: unknown email, wrong password, or a soft-deleted account (deliberately indistinguishable from each other — no user enumeration). |
| `EMAIL_NOT_VERIFIED` | 403 | Login: credentials correct but `email_verified = false`. |
| `ACCOUNT_LOCKED` | 423 | Login: `locked_until > now()`. `details`: `{ "lockedUntil": "...", "retryAfterSeconds": 812 }`. |
| `UNAUTHORIZED` | 401 | Missing, malformed, expired, or otherwise invalid JWT on an auth-required endpoint. |
| `NOT_FOUND` | 404 | Referenced resource doesn't exist (not expected to be hit often in M1's small endpoint set). |
| `INTERNAL_ERROR` | 500 | Unhandled server error. |

423 (`Locked`, WebDAV-originated but valid/registered in the HTTP status registry) is used
verbatim as the settled-requirement "distinguishable lockout status code" — not folded into
401, and Spring supports returning arbitrary registered status codes without extra
dependencies.

---

## 2. Endpoints

### 2.1 `POST /api/auth/register`

Auth required: no.

**Updated by the backend registration flow separation task (post-Milestone-7) — breaking
change from the original `application/json` contract below.** Single endpoint kept
(`role` still discriminates the two shapes), but the request body is now
`multipart/form-data`, not JSON, and Customer/Professional payloads are nested,
role-specific objects (`customer`/`professional`) rather than one flat object of
nullable fields. Validated conditionally in the service layer (each nested payload is
required *iff* `role` matches) plus `@Valid` cascading Bean Validation on whichever
nested object is present. Frontend consumers of the original JSON contract need to
switch to multipart and the new nested shape.

**Why multipart, not JSON:** a Professional registration requires a verification
document (§ below), and there's no authenticated session yet at registration time to
drive a separate pre-upload call the way `professionals.service.ProfessionalsService`'s
post-registration profile-image upload does — so the file travels as a part on this same
request instead.

**Request parts:**

| Part name | Content-Type | Required | Notes |
|---|---|---|---|
| `data` | `application/json` | always | The JSON payload below. |
| `verificationDocument` | `application/pdf` \| `image/jpeg` \| `image/png` | iff `data.role = PROFESSIONAL` | Ignored if sent for a `CUSTOMER` registration. |
| `profilePhoto` | `image/jpeg` \| `image/png` \| `image/webp` | never (optional) | Only meaningful when `data.role = PROFESSIONAL`; ignored otherwise. |

**`data` — customer:**
```json
{
  "role": "CUSTOMER",
  "fullName": "ישראל ישראלי",
  "email": "israel@example.com",
  "password": "at-least-8-chars",
  "customer": {
    "defaultAddress": {
      "city": "תל אביב",
      "street": "דיזנגוף",
      "houseNumber": "100",
      "apartment": "4",
      "floor": "2",
      "entrance": "A",
      "addressNotes": "כניסה מהחצר"
    },
    "phone": "0501234567"
  }
}
```

**`customer.phone`** — added by the professional weekly availability calendar design's M2
(2026-08-18, `docs/architecture/professional-weekly-calendar-design.md` §9.1). Required for a
`CUSTOMER` registration, same validation tier as `customer.defaultAddress`'s required fields
(sibling field on the same `customer` object, not nested inside `defaultAddress` — a phone
number is not an address component). **Not** collected for `PROFESSIONAL` registration —
`users.phone` stays `NULL` for that role, no field added to the professional payload.

**`data` — professional:**
```json
{
  "role": "PROFESSIONAL",
  "fullName": "דוד כהן",
  "email": "david@example.com",
  "password": "at-least-8-chars",
  "professional": {
    "categoryId": 1,
    "serviceArea": "תל אביב",
    "basePrice": 150.00,
    "subServiceIds": [3, 7],
    "workingHours": [
      { "weekday": 0, "enabled": true,  "startTime": "08:00", "endTime": "18:00" },
      { "weekday": 1, "enabled": true,  "startTime": "08:00", "endTime": "18:00" },
      { "weekday": 2, "enabled": true,  "startTime": "08:00", "endTime": "18:00" },
      { "weekday": 3, "enabled": true,  "startTime": "08:00", "endTime": "18:00" },
      { "weekday": 4, "enabled": true,  "startTime": "08:00", "endTime": "14:00" },
      { "weekday": 5, "enabled": false, "startTime": null,    "endTime": null },
      { "weekday": 6, "enabled": false, "startTime": null,    "endTime": null }
    ]
  }
}
```

**`professional.subServiceIds` / `professional.workingHours` — new and both required, added by
Production Roadmap MS1 (2026-08-22, governing decisions D4/D7).** This is a **breaking change**
to the professional registration payload: a request omitting either field is now `400`.

MS0 recorded the concrete defect this closes: registration created zero
`professional_sub_services` rows and zero `professional_working_hours` rows, so a professional
who had just registered was listed to customers while deriving an empty calendar — the customer
discovered the dead end at step 3 of 4. The fix collects the two missing pieces at the only
moment the platform has the registrant's attention. **Nothing is fabricated**: no default
working hours are invented and no sub-services are guessed (D4/D5). Both remain editable
afterwards through `PUT /api/professionals/me/sub-services`
(`api-contract-professionals-reviews.md` §11.3) and `PUT /api/availability/working-hours`
(`api-contract-availability.md`).

`workingHours` uses the **identical** `availability.dto.WorkingHoursItemRequest` record as the
edit endpoint — the same Java type, so the two surfaces cannot drift — and the identical
`WorkingHoursValidator.validateWeek` rules. `subServiceIds` is validated by the identical
`professionals.service.SubServiceSelectionValidator` the edit endpoint uses.

`confirmPassword` is deliberately not a field anywhere in this contract — it's
frontend-only validation (`password == confirmPassword` before submitting), never sent
to or persisted by the backend.

**Field validation:**

| Field | Rule |
|---|---|
| `role` | required, one of `CUSTOMER` \| `PROFESSIONAL`. **Changed by Production Roadmap MS1 (2026-08-22).** `ADMIN` is now a real `users.role` value and a real `UserRole` enum constant (`V40`), so it **does** parse — the earlier "unparseable to this enum" argument no longer holds and must not be relied on. What blocks it now is an **explicit guard**: `auth.service.AuthService#validateRoleSpecificFields` checks `role == ADMIN` first and throws `400 VALIDATION_ERROR` (field `role`, "must be CUSTOMER or PROFESSIONAL") immediately, before any other validation and before any row is written. Without that guard the operator role that approves professionals would be self-issuable by anyone able to reach this public, unauthenticated endpoint. Any *other* unknown value is still unparseable → `400 VALIDATION_ERROR`. |
| `fullName` | required, 2–150 chars (matches `users.full_name VARCHAR(150)`). |
| `email` | required, valid email format, ≤255 chars. Uniqueness checked case-insensitively against `ux_users_email_lower`. |
| `password` | required, **min 8 characters**. Same MVP-default judgment call as before, unchanged. |
| `customer` / `customer.defaultAddress` | required *iff* `role = CUSTOMER`; absent → `VALIDATION_ERROR` on `customer.defaultAddress`. |
| `customer.defaultAddress.city` / `.street` / `.houseNumber` | required, non-blank, size-capped (100/150/20 chars, matching `users.default_*` column lengths). |
| `customer.defaultAddress.apartment` / `.floor` / `.entrance` / `.addressNotes` | optional. |
| `customer.phone` | **New, M2.** Required, non-blank, ≤20 chars (matching `users.phone VARCHAR(20)`, `V28`). Read-only after registration — no edit endpoint exists in this API to update it, same as `defaultAddress`. |
| `professional.categoryId` | required *iff* `role = PROFESSIONAL`; must reference an existing `categories.id` (1–8, per the seeded `V10` list). Absent/invalid → `VALIDATION_ERROR`. |
| `professional.serviceArea` | required *iff* `role = PROFESSIONAL`; 1–150 chars. |
| `professional.basePrice` | required *iff* `role = PROFESSIONAL`; `> 0`, ≤2 decimal places. Same judgment call as before (PRD §1/§7.3/§7.4 price-on-card requirement), unchanged. |
| `professional.subServiceIds` | **New, MS1.** Required *iff* `role = PROFESSIONAL`; non-empty, no `null` entries, duplicates de-duplicated. Every id must exist **and belong to `professional.categoryId`'s own category** — a cross-category id is `400 CATEGORY_MISMATCH`, not a silent drop. Enforced by `professionals.service.SubServiceSelectionValidator`, the same component `PUT /api/professionals/me/sub-services` uses, reporting against the field path `professional.subServiceIds`. |
| `professional.workingHours` | **New, MS1.** Required *iff* `role = PROFESSIONAL`. **Exactly 7 entries**, weekdays `0`–`6` each present exactly once (`0` = Sunday, per `professional-weekly-calendar-design.md` §4.2); `enabled` non-null on every entry; when `enabled = true`, `startTime`/`endTime` are required and `endTime > startTime`; when `enabled = false`, times must be `null` (not `""`). **At least one enabled day** — an all-disabled week is `400`, because onboarding is not complete without a bookable week (D4). Enforced by `availability.service.WorkingHoursValidator.validateWeek` + `requireAtLeastOneEnabledDay`. Note the asymmetry, deliberate: the *edit* endpoint does **not** apply the at-least-one-enabled-day rule — an established professional switching every day off is going on holiday, a legitimate act the platform must not block; they simply stop being eligible until they switch a day back on. |
| `verificationDocument` (multipart part) | required *iff* `role = PROFESSIONAL`; absent/empty → `VALIDATION_ERROR`; unsupported content-type → `400 UNSUPPORTED_DOCUMENT_TYPE`; >8MB → `413 IMAGE_TOO_LARGE` (shared error code with image uploads). |
| `profilePhoto` (multipart part) | always optional; unsupported content-type → `400 UNSUPPORTED_IMAGE_TYPE`; >8MB → `413 IMAGE_TOO_LARGE`. |

**Behavior:**
1. Reject with `409 DUPLICATE_EMAIL` if `lower(email)` already exists in `users` (including
   soft-deleted rows — email uniqueness is enforced on the raw column regardless of
   `deleted_at`, since the unique index has no partial/`WHERE deleted_at IS NULL` clause;
   see §4 open item for the tension this creates with anonymized-email reuse).
2. Hash the password (BCrypt, see §3.2).
3. Insert the `users` row (`email_verified = false`, `role` as given). If
   `role = CUSTOMER`, the `default_city`/`default_street`/`default_house_number`/
   `default_apartment`/`default_floor`/`default_entrance`/`default_address_notes`
   columns (added by `V20__alter_users_add_default_address.sql`) are populated in the
   same insert.
4. If `role = PROFESSIONAL`: also insert the `professionals` row
   (`category_id`, `service_area`, `base_price`, and — **changed by Production Roadmap MS1,
   2026-08-22** — `approval_status = 'PENDING'`, set explicitly by
   `professionals.entity.Professional`'s constructor rather than left to the column DEFAULT.
   The previous text here, "`approval_status` defaults to `'APPROVED'` per the inert-column
   decision — nothing else to do, no workflow step", is **superseded and no longer true**: the
   account is now genuinely awaiting an operator decision and is invisible to customers until
   it gets one. No client-supplied field anywhere in `RegisterRequest` can influence this
   value), insert one `professional_sub_services` row per distinct `subServiceIds` entry and
   all 7 `professional_working_hours` rows from `workingHours` (MS1 — same transaction),
   upload `verificationDocument` and persist its key to
   `verification_document_key` (added by
   `V21__alter_professionals_add_verification_document.sql`), upload `profilePhoto` if
   supplied and persist its key to the pre-existing `profile_image_key`, plus
   an `sos_availability` row defaulting to `isAvailable = false` — see §4, added by the
   `V13__create_sos_availability.sql` schema-gap fix (2026-08-13, ahead of Milestone 4).
   All of the above (`users` + `professionals` + document upload) happens on one
   `@Transactional` service method — a failure at any step rolls back the whole
   registration, no orphaned `users` row.
5. Generate a 6-digit numeric verification code, insert into `verification_codes`
   (`purpose = 'EMAIL_VERIFICATION'`, `expires_at = now() + 15 minutes` — see §3.3 for the
   duration rationale), and dispatch it via the configured `EmailSender` (§3.3).
6. Return `201 Created`. **No JWT is issued here** — the account isn't usable until
   verified (login enforces `EMAIL_NOT_VERIFIED` otherwise), so registration and login are
   deliberately kept as separate steps rather than auto-logging-in an unverified account.

**Response `201`** (unchanged from the original contract):
```json
{
  "userId": 42,
  "role": "CUSTOMER",
  "email": "israel@example.com",
  "emailVerified": false
}
```

**Status codes**: `201` success · `400 VALIDATION_ERROR` · `400 CATEGORY_MISMATCH` (MS1 —
a `subServiceIds` entry outside `professional.categoryId`'s category) ·
`400 UNSUPPORTED_DOCUMENT_TYPE` · `400 UNSUPPORTED_IMAGE_TYPE` · `413 IMAGE_TOO_LARGE` ·
`409 DUPLICATE_EMAIL`.

**What a professional can do after a `201` (MS1).** They verify their email and log in
normally; `PENDING` is not a limbo. They may edit their profile, sub-services, working hours
and SOS-availability toggle. What they cannot do until an operator approves them — and until
their onboarding is complete — is appear in customer matching, receive Standard or SOS
requests, or be presented as bookable. See `api-contract-professionals-reviews.md` §12 for the
operator side and `data-model.md` §2.4 for the eligibility rule.

---

### 2.2 `POST /api/auth/verify`

Auth required: no (caller doesn't have a token yet at this point in the flow).

**Request:**
```json
{
  "email": "israel@example.com",
  "code": "483920"
}
```

**Behavior** (evaluated in this order):
1. Look up user by `lower(email)`. If not found → `400 INVALID_CODE` (deliberately generic
   — do not reveal whether the email is registered).
2. If `users.email_verified = true` already → `409 EMAIL_ALREADY_VERIFIED`.
3. Look up the most recent `verification_codes` row for
   `(user_id, purpose = 'EMAIL_VERIFICATION', code = :code)`.
   - Not found (wrong code) → `400 INVALID_CODE`.
   - Found but `consumed_at IS NOT NULL` → `409 CODE_ALREADY_CONSUMED`.
   - Found but `expires_at <= now()` → `410 CODE_EXPIRED`.
   - Otherwise: set `consumed_at = now()` on the code row and `users.email_verified = true`
     in the same transaction.

**Response `200`:**
```json
{
  "userId": 42,
  "emailVerified": true
}
```

**Status codes**: `200` success · `400 INVALID_CODE` · `410 CODE_EXPIRED` ·
`409 EMAIL_ALREADY_VERIFIED` / `409 CODE_ALREADY_CONSUMED`.

**Note, added Milestone 7 (2026-08-14), not a bug — clarifying a QA finding so a future
reader doesn't mistake this for dead/buggy code.** `409 CODE_ALREADY_CONSUMED` (behavior
step 3's second bullet, `consumed_at IS NOT NULL`) is defined for the code-lookup path but
is **structurally unreachable via the live API today**, given this endpoint's own
documented check order above: step 2 (`emailVerified` already `true` → `409
EMAIL_ALREADY_VERIFIED`) always runs *before* step 3's code lookup, and consuming a code
always sets `users.email_verified = true` in the same transaction that sets its
`consumed_at` (step 3's last bullet). So by the time a *second* submission of an
already-consumed code could reach step 3's `consumed_at IS NOT NULL` check, step 2 has
already short-circuited the request with `409 EMAIL_ALREADY_VERIFIED` instead. There is
also no "resend code" endpoint (see the out-of-scope note below) that could otherwise
create a scenario with a genuinely-consumed-but-not-yet-verified state to reach this branch
through. QA confirmed this live against `AuthService.verify` — re-submitting an
already-consumed code returns `409 EMAIL_ALREADY_VERIFIED`, never `409
CODE_ALREADY_CONSUMED` — and confirmed the framing above matches this endpoint's own
already-documented check order, i.e. this is expected/correct behavior as designed, not a
regression or an unreachable-code defect to fix.

**Out of scope, flagged not built**: a "resend verification code" endpoint. Nothing in the
task brief or `overview.md`/`data-model.md` asks for one; if a code expires (15 min) the
user currently has no self-service way to get a new one in v1.0. Worth raising to the user
— see §4.

---

### 2.3 `POST /api/auth/login`

Auth required: no.

**Request:**
```json
{
  "email": "israel@example.com",
  "password": "at-least-8-chars"
}
```

**Behavior** (evaluated in this exact order — order matters for the lockout semantics):
1. Look up user by `lower(email)`. If not found, **or** `deleted_at IS NOT NULL` → `401
   INVALID_CREDENTIALS`. (Soft-deleted accounts are indistinguishable from nonexistent
   ones at login — no separate error code is exposed for "this account was deleted.")
2. If `locked_until IS NOT NULL AND locked_until > now()` → `423 ACCOUNT_LOCKED`
   immediately, **without checking the password and without incrementing
   `failed_login_attempts` further** (so a locked-out user hammering the login endpoint
   doesn't extend their own lockout). Response `details`:
   ```json
   { "lockedUntil": "2026-08-13T13:05:00Z", "retryAfterSeconds": 812 }
   ```
3. If `locked_until IS NOT NULL AND locked_until <= now()` (lock has time-expired): reset
   `failed_login_attempts = 0` and `locked_until = NULL` before continuing — this is the
   "fresh 5-attempt budget after the window" interpretation of `data-model.md`'s
   time-based-only lockout decision. **Judgment call, flagged**: the data model doc settles
   *that* lockout auto-expires after 15 minutes but doesn't spell out exactly when the
   counter resets; resetting at the first login attempt after expiry (rather than only on
   a *successful* one) is the interpretation used here, and is a one-line service-layer
   detail, not a schema question.
4. Compare `password` against `password_hash` (BCrypt). If it doesn't match:
   - Increment `failed_login_attempts`.
   - If the new count reaches 5: set `locked_until = now() + 15 minutes`, return `423
     ACCOUNT_LOCKED` (this response's `retryAfterSeconds` will be ~900).
   - Otherwise return `401 INVALID_CREDENTIALS`.
5. If password matches but `email_verified = false` → `403 EMAIL_NOT_VERIFIED`.
6. Success: reset `failed_login_attempts = 0`, `locked_until = NULL`, issue a JWT (§3.1),
   return `200`.

**Response `200`:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "user": {
    "id": 42,
    "fullName": "ישראל ישראלי",
    "email": "israel@example.com",
    "role": "CUSTOMER"
  }
}
```

**Status codes**: `200` success · `401 INVALID_CREDENTIALS` · `403 EMAIL_NOT_VERIFIED` ·
`423 ACCOUNT_LOCKED`.

---

### 2.4 `GET /api/users/me`

Auth required: **yes**.

Minimal addition beyond the brief's explicit list, needed to make the contract usable
end-to-end (frontend needs a way to fetch "who am I / what role am I" after login/on app
load, rather than only ever trusting decoded JWT claims client-side).

**Response `200` (customer, with a saved default address):**
```json
{
  "id": 42,
  "fullName": "ישראל ישראלי",
  "email": "israel@example.com",
  "role": "CUSTOMER",
  "emailVerified": true,
  "defaultAddress": {
    "city": "תל אביב",
    "street": "אלנבי",
    "houseNumber": "12",
    "apartment": "4",
    "floor": "2",
    "entrance": "א",
    "addressNotes": "קוד כניסה 1234"
  },
  "phone": "0501234567"
}
```

**Response `200` (professional)** — includes the `professionals` row fields:
```json
{
  "id": 43,
  "fullName": "דוד כהן",
  "email": "david@example.com",
  "role": "PROFESSIONAL",
  "emailVerified": true,
  "professional": {
    "categoryId": 1,
    "serviceArea": "תל אביב",
    "basePrice": 150.00,
    "profileImageUrl": null
  },
  "defaultAddress": null,
  "phone": null
}
```

**`defaultAddress`** — added by the MS3/MS4 product-corrections pass (2026-08-17), a nested
object (`DefaultAddressInfo`), mirroring `professional`'s own "absent means no such object"
convention: `null` for a `PROFESSIONAL` caller (the `users.default_*` columns are always null
for that role) and also `null` for a `CUSTOMER` with no recorded default city (a pre-`V20`
account). When present, always carries all 7 fields (`city`/`street`/`houseNumber` are
required at registration per `V20`; `apartment`/`floor`/`entrance`/`addressNotes` may
individually be `null`). Initially set once at registration (§2.1 step 3, `V20`'s
`default_city`/`default_street`/`default_house_number`/`default_apartment`/`default_floor`/
`default_entrance`/`default_address_notes` columns); **amended by the MS10 profile redesign
(2026-08-19): now also editable by a `CUSTOMER` caller via `PUT /api/users/me` (§2.6)** — no
longer read-only. See `docs/architecture/ms3-ms4-corrections-design.md` §1.

**`phone`** — added by the professional weekly availability calendar design's M2
(2026-08-18). A top-level string field (not nested), mirroring `defaultAddress`'s exact
nullability/placement convention: `null` for a `PROFESSIONAL` caller, and `null` for a
`CUSTOMER` with no recorded phone (a pre-`V28` account). Initially set once at registration
(§2.1's `customer.phone` field, `V28`'s `users.phone` column); **amended by the MS10 profile
redesign (2026-08-19): now also editable by a `CUSTOMER` caller via `PUT /api/users/me`
(§2.6)** — no longer read-only. This is the customer viewing **their own** phone on their own
account screen — an entirely separate, ungated concern from the order-based
professional-visibility rule `docs/architecture/api-contract-bookings.md` §2.8 adds
(`OrderDetailResponse.customerPhone`). See
`docs/architecture/professional-weekly-calendar-design.md` §9.1.

**`professional.profileImageUrl`** — added by the MS10 profile redesign (2026-08-19), so a
professional caller can see their own existing photo on the shared `/profile` page (`app/
ProfilePage.tsx`), read-only there. `null` when no photo has been uploaded, otherwise a
presigned URL resolved the same way `GET /api/professionals/me`'s `profileImageUrl` already
is. See `docs/architecture/product-ms10-profile-redesign-design.md` §6.

**Status codes**: `200` success · `401 UNAUTHORIZED` (missing/invalid/expired token, or the
token's user no longer exists / is soft-deleted).

---

### 2.5 `DELETE /api/users/me`

Auth required: **yes**.

Soft-delete + PII anonymization per the settled requirement — no hard `DELETE`.

**Behavior:**
1. Resolve the caller from the JWT (`sub` claim → user id).
2. Set:
   - `deleted_at = now()`
   - `full_name = 'Deleted User'`
   - `email = 'deleted-user-' || id || '@pronto.invalid'` (deterministic, guaranteed unique
     against `ux_users_email_lower` since `id` is a PK; `.invalid` is the RFC 2606 reserved
     TLD for addresses that must never resolve/receive mail — chosen specifically so an
     anonymized row can never collide with, or be mistaken for, a real registerable
     address).
   - `password_hash` is **not** cleared (no login should ever reach a password check for
     this row again anyway, since step 1 of `/login` already excludes `deleted_at IS NOT
     NULL` rows via `401 INVALID_CREDENTIALS`; clearing it adds no security value and
     avoids a nullability change to a `NOT NULL` column).
3. Does **not** touch `professionals`, `issues`, or `orders` rows — those are `RESTRICT`-FK
   business records with independent meaning (per `data-model.md` §0's FK policy) and are
   explicitly out of scope for this soft-delete; only `users` PII fields are anonymized.
   **Flagged, not solved here**: if the deleted user was a professional, their
   `professionals` row (service area, category, price) survives untouched and would still
   appear in Standard/SOS listings post-deletion unless Milestone 3/4's listing query is
   also taught to join against `users.deleted_at IS NULL`. Noting this dependency now so
   it isn't missed later — not this milestone's endpoint to fix, but the listing queries
   need to account for it.
4. Return `204 No Content`.

**Idempotency note**: a second `DELETE` call with the same (still-unexpired) token cannot
actually succeed twice in practice — see §3.1's per-request "is this user still active"
check: once `deleted_at` is set, the very next request (including a repeated `DELETE`)
fails auth with `401 UNAUTHORIZED` before reaching the handler at all. This is a direct,
intentional consequence of the token-revocation design in §3.1, not a separate
idempotency guard.

**Status codes**: `204` success · `401 UNAUTHORIZED`.

---

### 2.6 `PUT /api/users/me`

Added by the MS10 profile redesign (2026-08-19, `docs/architecture/product-ms10-profile-
redesign-design.md` §4). Auth required: **yes**. Role: **`CUSTOMER` only** — a `PROFESSIONAL`
caller gets `403 FORBIDDEN` (route-level gate, `users.config.UsersWebConfig`, plus a
defense-in-depth re-check in `UsersService.updateMe`). Does not cover the photo (customer
profile photo upload is out of scope for this milestone — see §3.1 "Reading A" of the design
doc).

**Request body:**
```json
{
  "fullName": "ישראל ישראלי",
  "phone": "0501234567",
  "defaultAddress": {
    "city": "תל אביב",
    "street": "אלנבי",
    "houseNumber": "12",
    "apartment": "4",
    "floor": "2",
    "entrance": "א",
    "addressNotes": "קוד כניסה 1234"
  }
}
```
- `fullName`: `@NotBlank @Size(max = 150)`.
- `phone`: `@NotBlank @Size(max = 20)` — required on every call (mirrors the existing
  registration-time requirement for a `CUSTOMER`; there is no "leave phone unset" state).
- `defaultAddress`: `@NotNull @Valid`, always required in full — no partial-address update.
  `city`/`street`/`houseNumber` are `@NotBlank`; `apartment`/`floor`/`entrance`/
  `addressNotes` are optional. Same shape/validation as `auth.dto.DefaultAddressRequest`
  (registration), reused here as an independently-defined nested record
  (`users.dto.UpdateUserMeRequest.Address`) to avoid a new `users -> auth` package
  dependency. Also usable by a pre-`V20` customer (`defaultAddress: null` on `GET /me`) to
  supply a default address for the first time — no separate "add address" endpoint needed.

**Behavior:**
1. Defense-in-depth `403 FORBIDDEN` if the caller's role isn't `CUSTOMER`.
2. Load the caller's active `users` row.
3. Set `full_name`, `phone`, and all 7 `default_*` columns via the entity's existing setters
   — no new column, no new migration.
4. Save, then return the same shape `GET /api/users/me` returns (`UserMeResponse`) — no new
   response DTO.

**Status codes**: `200` success · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` ·
`403 FORBIDDEN`. No new `ErrorCode` values.

**Amendment note**: this reverses §2.4's previous "read-only, no endpoint exists" language
for `defaultAddress`/`phone` (see those fields' entries in §2.4, now updated). Deliberate,
not a silent reinterpretation — `orders.service_city`/`service_street`/
`service_house_number`/`service_apartment` are captured as their own snapshot at
order-creation time (`V18`), decoupled from `users.default_*`, so retroactively editing a
customer's saved default address has no correctness impact on any existing or in-flight
order. `email` stays read-only (excluded on purpose — changing it would need to re-trigger
email verification, a materially different, unrequested feature).

---

## 3. Cross-cutting mechanism decisions

### 3.1 Token mechanism: JWT

**Decision: JWT**, per `overview.md` §3.2/§3.7's "JWT or an equivalent stateless token" —
no reason surfaced to prefer an alternative (opaque server-side session tokens would need a
session store, which is exactly the stateless-scaling tradeoff §3.2 already decided
against for the 1,000-concurrent-user target).

- **Library**: `io.jsonwebtoken` (`jjwt`), 0.12.x line — **not currently in `pom.xml`,
  needs to be added by `pronto-coding`**:
  ```xml
  <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-api</artifactId>
      <version>0.12.6</version>
  </dependency>
  <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-impl</artifactId>
      <version>0.12.6</version>
      <scope>runtime</scope>
  </dependency>
  <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-jackson</artifactId>
      <version>0.12.6</version>
      <scope>runtime</scope>
  </dependency>
  ```
  (Pin the exact latest 0.12.x patch at implementation time.) Chosen over alternatives
  (Nimbus JOSE+JWT, Spring Security's own OAuth2 resource-server JWT support) as the
  simplest, most widely-used option for "issue and verify our own signed tokens" without
  pulling in an OAuth2/OIDC framework this app doesn't need.
- **Algorithm**: HS256 (symmetric). Sufficient because this is a single monolith issuing
  and validating its own tokens — there's no separate resource server that would benefit
  from asymmetric (RS256) verification. Secret sourced from an environment
  variable/Spring config property (e.g. `pronto.jwt.secret`), **never committed**.
- **Claims**:
  | Claim | Value |
  |---|---|
  | `sub` | user id (string form of `users.id`) |
  | `role` | `CUSTOMER` \| `PROFESSIONAL` |
  | `iat` | issued-at |
  | `exp` | expiry |
- **Expiry**: **24 hours**. No refresh-token flow in v1.0 (flagged as a deliberate scope
  cut, not an oversight — see §4) — a user must log in again after 24h. Chosen as a
  pragmatic middle ground: short enough to bound a stolen-token's blast radius, long
  enough that a customer/professional doesn't get logged out mid-session on a short-polling
  app they may leave open in a browser tab for a work day.
- **Revocation / the deleted-account problem**: a pure stateless JWT can't be revoked
  before its natural expiry without a server-side blocklist (which reintroduces the
  session-store cost JWT was chosen to avoid). **Decision**: the auth filter loads the user
  row by the token's `sub` on **every** request (a single indexed PK lookup — cheap, and
  acceptable at the 1,000-concurrent-user target) and rejects with `401 UNAUTHORIZED` if
  `deleted_at IS NOT NULL`. This gives effectively-immediate revocation for the one case
  this milestone actually needs (account deletion) without building a general-purpose
  blocklist/logout endpoint for the broader "arbitrary token compromise" case, which is
  explicitly not being solved in v1.0 — see §4.
- **Logout**: client-side token discard only (no server endpoint) — consistent with the
  above; there is nothing for a `/logout` endpoint to do server-side without a blocklist.

### 3.2 Password hashing & Spring Security dependency choice

**Decision: pull in the full `spring-boot-starter-security`**, not just the standalone
`spring-security-crypto` module.

Reasoning: `BCryptPasswordEncoder` alone only needs `spring-security-crypto`, but this
milestone also needs a JWT-validating request filter and route-level access rules (public
`/api/auth/**` vs. authenticated `/api/users/me`), and — looking ahead, not
speculatively — **every later milestone** needs role-gated endpoints too (professional
accept/reject in M3/M4, the professional dashboard in M6 restricted to `role =
PROFESSIONAL`, etc.). Spring Security's `SecurityFilterChain` +
`@PreAuthorize`/`hasRole(...)` gives declarative, centrally-configured request matching for
all of that; hand-rolling an `OncePerRequestFilter` plus manual header-parsing/role-checks
duplicated across every future controller is more security-critical code to get right, not
less. The one-time cost is explicit Java config to disable the parts of Spring Security
this app doesn't want (form-login page, CSRF — not needed for a stateless token API,
default `permitAll`/`authenticated` matchers per route).

**Implementation note, flagged so Milestone 0's acceptance criteria isn't silently broken**:
Spring Security defaults to securing *all* endpoints once the starter is on the classpath,
including `/actuator/health` (Milestone 0's health-check acceptance criterion). The
security config **must** explicitly `permitAll()` `/actuator/health` and `/api/auth/**`,
and require authentication only for `/api/users/me` (and whatever later milestones add) —
called out here so `pronto-coding` doesn't regress M0 while building M1.

Password hashing: `BCryptPasswordEncoder` (Spring Security default strength/cost factor of
10) — never plaintext, per the already-settled requirement.

### 3.3 Email delivery — explicitly a mock/log-only sender for Milestone 1

**Decision, stated explicitly per the task brief's instruction not to let this be a silent
shortcut**: Milestone 1 does **not** wire up real SMTP/SES delivery. Instead:

- An `EmailSender` interface (e.g. `void sendVerificationCode(String toEmail, String
  code)`) lives in the `auth` package (or `common`, `pronto-coding`'s call), with a single
  `LoggingEmailSender` implementation that logs the recipient + code at `INFO` level
  instead of sending anything. This is the **default and only** implementation for
  Milestone 1 — no `spring-boot-starter-mail` dependency is added to `pom.xml` this
  milestone.
- This lets a developer manually test the register → verify flow end-to-end by reading the
  code out of the application log, with zero external dependency (no mail server, no AWS
  SES account/credentials needed yet).
- Real delivery (SES or SMTP) is deferred to whichever milestone actually needs
  externally-visible email (Milestone 5, "Notifications & real-time status", already owns
  "email dispatch" per `overview.md` §4's `notifications` package row) — at that point the
  same `EmailSender` interface should be swapped for a real implementation behind a config
  flag (e.g. `pronto.mail.mode=log|smtp`), not rebuilt from scratch.

**Verification code expiry**: 15 minutes. Not specified by any source document; chosen for
consistency with the already-settled `locked_until` 15-minute lockout window rather than
inventing an unrelated third duration. Flagged as a judgment call, not a hard requirement.

---

## 4. Open items / risks (flagged, not silently resolved)

- **Genuine contradiction found in already-applied Milestone 0 schema — both gaps since
  fixed.** `data-model.md` §2.6 and §3 item 5 (both dated "DECIDED, user override,
  2026-08-12") specify a dedicated `sos_availability` table, separate from
  `availability_slots`. The originally-applied migration,
  `backend/src/main/resources/db/migration/V5__create_availability_slots.sql`, had
  implemented the **originally-rejected** single-table design instead (its own header
  comment said the table was "used for both Standard scheduling and SOS 'currently
  available' matching"). Closed via `V13__create_sos_availability.sql` (2026-08-13, done
  ahead of Milestone 4 specifically to unblock it) — registration (§2.1 step 4) now inserts
  the `sos_availability` row too. Relatedly, `V8__create_orders.sql`'s `order_status` CHECK
  constraint had implemented the **superseded 6-status** model (no `REJECTED`),
  contradicting `data-model.md` §2.9/§3 item 10 — closed via
  `V11__alter_orders_status_add_rejected.sql` as part of Milestone 3.
- **No password-reset flow.** Nothing in the source docs describes one (the
  `verification_codes.purpose` column was deliberately left as a single-value CHECK
  precisely because of this, per `data-model.md` §2.3), so none is designed here. Worth
  confirming with the user whether this is truly out of v1.0 scope or just not yet
  specified — a user who forgets their password currently has no self-service recovery
  path.
- **No "resend verification code" endpoint** (noted inline in §2.2 too). A user whose code
  expires (15 min) has no way to request a new one without re-registering, which would hit
  `409 DUPLICATE_EMAIL`. Flagging as a real end-to-end usability gap, not building it
  silently since it wasn't asked for in the brief's endpoint list.
- **No refresh-token / logout-with-revocation mechanism** (noted inline in §3.1). Accepted
  MVP limitation given the 24h expiry and the deleted-account-specific revocation check;
  flagging so it's a visible, documented tradeoff rather than a gap discovered later.
- **Anonymized email vs. the unique index — confirmed not a defect, noted for clarity.**
  `DELETE /api/users/me` overwrites the real `email` with a synthetic
  `deleted-user-{id}@pronto.invalid` value as part of anonymization. Because the original
  address is actually replaced (not just flagged), it's immediately free for a new
  registration to reuse — `ux_users_email_lower`'s lack of a `WHERE deleted_at IS NULL`
  clause doesn't cause a permanent reuse block, since by the time deletion completes the
  original email string no longer exists in the table. Recorded here only because the
  interaction between soft-delete and a non-partial unique index is easy to mis-flag as a
  bug at a glance.

---

## MS4 (2026-08-24) — professional registration payload

`POST /api/auth/register`'s nested `professional` object changed shape. The fields below
**supersede** the `professional.categoryId` / `professional.serviceArea` rows above.

```jsonc
"professional": {
  "categoryIds":     [1, 8],      // >= 1; a professional may serve several trades
  "serviceRegionId": 4,           // canonical service_regions.id -- never free text
  "serviceCityIds":  [40, 41],    // >= 1; every one inside serviceRegionId
  "baseCityId":      40,          // must be one of serviceCityIds
  "basePrice":       250.00,
  "subServiceIds":   [1, 31],
  "workingHours":    [ /* 7 entries, unchanged */ ]
}
```

| Field | Rule |
|---|---|
| `professional.categoryIds` | Required *iff* `role = PROFESSIONAL`; non-empty, no `null` entries, duplicates de-duplicated. Every id must exist in `categories` → otherwise `400 VALIDATION_ERROR` naming `professional.categoryIds`. |
| `professional.serviceRegionId` | Required; must exist in `service_regions` (see `GET /api/service-areas`). |
| `professional.serviceCityIds` | Required, non-empty; every id must exist in `service_cities` **and belong to `serviceRegionId`** — a cross-region city is `400 VALIDATION_ERROR`, reported against `professional.serviceCityIds`. |
| `professional.baseCityId` | Required; must be one of `serviceCityIds`. This is the city `matching` measures travel from, so it has to be a city the professional actually serves. |
| `professional.subServiceIds` | Unchanged except that "belongs to `professional.categoryId`'s own category" is now "belongs to **one of** `professional.categoryIds`". Cross-category is still `400 CATEGORY_MISMATCH`. |

All of the above are validated **before any row is written**, by
`locations.service.ServiceCoverageValidator` and
`professionals.service.ProfessionalCoverageService` — the same components
`PUT /api/professionals/me` uses, so registration and the profile edit cannot enforce different
rules. Every problem in a selection is reported at once rather than one resubmission at a time.

### `GET /api/service-areas` — new, public

The closed region/city catalogue registration and the profile editor choose from. Unauthenticated
for the same reason `GET /api/categories` is: the professional registration wizard needs it before
an account exists, and it is a list of Israeli city names.

```jsonc
[
  { "id": 4, "code": "gush_dan", "nameHe": "גוש דן", "nameEn": "Gush Dan", "displayOrder": 4,
    "cities": [ { "id": 40, "code": "tel_aviv", "nameHe": "תל אביב", "nameEn": "Tel Aviv", "displayOrder": 1 } ] }
]
```

7 regions, 96 cities. A client fetches the whole thing once and filters locally; `cities` nested
under its region **is** the region→city filter, so no client needs a region→city map of its own.


---

## Production MS1 (2026-08-25) — Authentication & Contact Verification

**This section supersedes §2.1–§2.3 and parts of §3.1.** Where the two disagree, this one is
current; the earlier text is kept as the record of what the contract was before this milestone.
Full rationale: `docs/production-roadmap/reports/prod-MS1-report.md`.

### The rule everything else follows from

A password alone never issues a session. Exactly two endpoints return a token —
`POST /api/auth/login/otp` and `POST /api/auth/verify-phone` — and both sit strictly behind a
redeemed one-time password.

### Flows

```
register        -> account created, email code sent           (no token)
verify-email    -> email proved, phone code sent              (no token)
verify-phone    -> phone proved, registration complete        -> TOKEN

login           -> password checked, code sent to the channel
                   matching the identifier used               (no token)
login/otp       -> code redeemed                              -> TOKEN
```

### Shared response shapes

`AuthStepResponse` — returned by `register`, `verify-email`, `verify-phone`, `login`, `login/otp`:

```json
{
  "nextStep": "VERIFY_EMAIL | VERIFY_PHONE | LOGIN_OTP | LOGIN | AUTHENTICATED",
  "challenge": { "challengeId": "uuid", "channel": "EMAIL|SMS",
                 "destinationMasked": "d***@example.com",
                 "expiresInSeconds": 900, "delivered": true },
  "session":   { "token": "...", "tokenType": "Bearer", "expiresIn": 86400, "user": {} },
  "emailVerified": false,
  "phoneVerified": false
}
```

Exactly one of `challenge`/`session` is populated. `AUTHENTICATED` carries a session, `LOGIN` carries
neither, every other step carries a challenge.

`OtpChallenge` alone is returned by `otp/resend`, `phone/capture` and `password-reset/request`.

A challenge is addressed only by its opaque `challengeId`. No OTP endpoint takes an email address or
a phone number, which is what keeps these flows from becoming an account-existence oracle.

### Endpoints

| Method | Path | Auth | Body | Returns |
|---|---|---|---|---|
| POST | `/api/auth/register` | public | multipart; `data` gains top-level **`phone`**, required for **both** roles; `customer.phone` removed | `201 AuthStepResponse` (`VERIFY_EMAIL`) |
| POST | `/api/auth/verify-email` | public | `{challengeId, code}` | `AuthStepResponse` (`VERIFY_PHONE` or `LOGIN`) |
| POST | `/api/auth/verify-phone` | public | `{challengeId, code}` | `AuthStepResponse` (`AUTHENTICATED`) |
| POST | `/api/auth/login` | public | `{identifier, password}` | `AuthStepResponse` (`LOGIN_OTP` or `VERIFY_EMAIL`) |
| POST | `/api/auth/login/otp` | public | `{challengeId, code}` | `AuthStepResponse` (`AUTHENTICATED`) |
| POST | `/api/auth/otp/resend` | public | `{challengeId}` | `OtpChallenge` |
| POST | `/api/auth/phone/capture` | **JWT** | `{phone}` | `OtpChallenge` |
| POST | `/api/auth/password-reset/request` | public | `{identifier}` | `OtpChallenge`, always |
| POST | `/api/auth/password-reset/confirm` | public | `{challengeId, code, newPassword}` | `204` |

`POST /api/auth/verify` is **removed**.

`identifier` is an email address **or** a phone number; the server decides which. Phone resolution
additionally requires `phone_verified` — an unverified number is contact detail, not a credential.

`/api/auth/phone/capture` is the one authenticated route under this prefix and is matched
`.authenticated()` *before* the `permitAll` on `/api/auth/**` (Spring Security's first match wins).

### `GET /api/users/me`

`phone` is now returned for **every** role, in canonical E.164 (it used to be blanked for a
`PROFESSIONAL`). New field `phoneVerified: boolean`.

### OTP rules

6 digits from `SecureRandom`; SHA-256 at rest, never plaintext; single use; **5 attempts per
challenge**; TTL 10 min (login) / 15 min (verification, reset); resend replaces its predecessor;
60 s resend cooldown; 5 codes per purpose per user per hour. The code is never logged outside
`pronto.environment=local`.

### New error codes

`DUPLICATE_PHONE` (409) · `PHONE_VERIFICATION_REQUIRED` (403) · `PHONE_ALREADY_VERIFIED` (409) ·
`OTP_ATTEMPTS_EXCEEDED` (429) · `OTP_DELIVERY_FAILED` (502).

`PHONE_VERIFICATION_REQUIRED` is returned by issue creation, order creation and SOS activation for an
account whose phone is unverified — deliberately not a generic `403`, because the caller *may* do
this as soon as they finish a thirty-second step, and the client needs to route them there rather
than show a dead end. Professional marketplace eligibility enforces the same requirement differently:
it is folded into `ProfessionalEligibility.ELIGIBLE_JPQL`, so an unverified professional is simply
not discoverable.

### Enumeration

`password-reset/request` answers identically for accounts that exist and accounts that do not,
including when rate-limited, and always reports `delivered: true`. An unknown identifier receives a
well-formed challenge id that refers to nothing and fails at confirm exactly as a wrong code does.
The accepted cost: a user who mistypes their address is told a code was sent and never receives one.
