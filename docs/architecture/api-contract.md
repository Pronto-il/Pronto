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
| `CODE_ALREADY_CONSUMED` | 409 | The matched code's `consumed_at` is already set (already used). |
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

Single endpoint, `role` field discriminates the two shapes. Not modeled as two separate
DTOs at the JSON level (the wire format is one flat object) — validated conditionally in
the service layer (professional-only fields are required *iff* `role == PROFESSIONAL`),
not via unconditional Bean Validation annotations, since those fields don't exist at all
for a customer registration.

**Request — customer:**
```json
{
  "role": "CUSTOMER",
  "fullName": "ישראל ישראלי",
  "email": "israel@example.com",
  "password": "at-least-8-chars"
}
```

**Request — professional:**
```json
{
  "role": "PROFESSIONAL",
  "fullName": "דוד כהן",
  "email": "david@example.com",
  "password": "at-least-8-chars",
  "categoryId": 1,
  "serviceArea": "תל אביב",
  "basePrice": 150.00
}
```

**Field validation:**

| Field | Rule |
|---|---|
| `role` | required, one of `CUSTOMER` \| `PROFESSIONAL`. |
| `fullName` | required, 2–150 chars (matches `users.full_name VARCHAR(150)`). |
| `email` | required, valid email format, ≤255 chars. Uniqueness checked case-insensitively against `ux_users_email_lower`. |
| `password` | required, **min 8 characters**. **Assumption, flagged**: no source document specifies a password policy; 8 chars / no additional complexity rule is a deliberately simple MVP default, easy to tighten later without a migration (it's not a stored/derived column, just request validation). |
| `categoryId` | required *iff* `role = PROFESSIONAL`; must reference an existing `categories.id` (1–8, per the seeded `V10` list). Absent/invalid → `VALIDATION_ERROR`. |
| `serviceArea` | required *iff* `role = PROFESSIONAL`; 1–150 chars. |
| `basePrice` | required *iff* `role = PROFESSIONAL`; `> 0`, ≤2 decimal places. **API-layer decision, narrower than the DB**: `professionals.base_price` is nullable in the schema (to allow future edit-only-later flexibility), but this endpoint requires it at registration time, because PRD §1/§7.3/§7.4 show a price offer on every professional's card *before* any request is sent — a professional with a `NULL` price would render incorrectly on Standard/SOS listing screens (Milestones 3–4). Flagged as a judgment call, not a hard requirement from a source doc. |

**Behavior:**
1. Reject with `409 DUPLICATE_EMAIL` if `lower(email)` already exists in `users` (including
   soft-deleted rows — email uniqueness is enforced on the raw column regardless of
   `deleted_at`, since the unique index has no partial/`WHERE deleted_at IS NULL` clause;
   see §4 open item for the tension this creates with anonymized-email reuse).
2. Hash the password (BCrypt, see §3.2).
3. Insert the `users` row (`email_verified = false`, `role` as given).
4. If `role = PROFESSIONAL`: also insert the `professionals` row
   (`category_id`, `service_area`, `base_price`, `approval_status` defaults to
   `'APPROVED'` per the inert-column decision — nothing else to do, no workflow step).
   **Does not** insert an `sos_availability` row — see §4, this is a flagged schema gap,
   not a silent omission.
5. Generate a 6-digit numeric verification code, insert into `verification_codes`
   (`purpose = 'EMAIL_VERIFICATION'`, `expires_at = now() + 15 minutes` — see §3.3 for the
   duration rationale), and dispatch it via the configured `EmailSender` (§3.3).
6. Return `201 Created`. **No JWT is issued here** — the account isn't usable until
   verified (login enforces `EMAIL_NOT_VERIFIED` otherwise), so registration and login are
   deliberately kept as separate steps rather than auto-logging-in an unverified account.

**Response `201`:**
```json
{
  "userId": 42,
  "role": "CUSTOMER",
  "email": "israel@example.com",
  "emailVerified": false
}
```

**Status codes**: `201` success · `400 VALIDATION_ERROR` · `409 DUPLICATE_EMAIL`.

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

**Response `200` (customer):**
```json
{
  "id": 42,
  "fullName": "ישראל ישראלי",
  "email": "israel@example.com",
  "role": "CUSTOMER",
  "emailVerified": true
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
    "basePrice": 150.00
  }
}
```

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

- **Genuine contradiction found in already-applied Milestone 0 schema, not part of this
  milestone's scope to fix, but blocking for later milestones — flagging to `pronto-lead`
  per instructions.** `data-model.md` §2.6 and §3 item 5 (both dated "DECIDED, user
  override, 2026-08-12") specify a dedicated `sos_availability` table, separate from
  `availability_slots`. The actual applied migration,
  `backend/src/main/resources/db/migration/V5__create_availability_slots.sql`, implements
  the **originally-rejected** single-table design instead — its own header comment says the
  table is "used for both Standard scheduling and SOS 'currently available' matching," and
  no `V11__create_sos_availability.sql` (or similar) exists. Relatedly,
  `V8__create_orders.sql`'s `order_status` CHECK constraint and header comment still
  implement the **superseded 6-status** model (no `REJECTED`), contradicting
  `data-model.md` §2.9/§3 item 10 and `overview.md`'s now-7-status "Booking statuses" row
  (also dated 2026-08-12). Both gaps predate this task and are **not altered by this
  doc** (Milestone 0 migrations were explicitly out of bounds for this task) — this
  endpoint contract works around the `sos_availability` gap by simply not creating that
  row at professional-registration time (§2.1 step 4) rather than silently inventing a
  migration. Both gaps need a follow-up migration + `pronto-lead` sign-off on sequencing —
  the `sos_availability` gap blocks Milestone 4 (SOS) and Milestone 6 (dashboard toggle);
  the `order_status`/`REJECTED` gap blocks Milestone 3/4's accept/reject logic. Neither
  blocks Milestone 1 itself.
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
