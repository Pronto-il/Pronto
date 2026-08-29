# `auth`

## Purpose

Registration, login, email verification codes, password hashing, account lockout, JWT
issuance/validation, and Spring Security wiring for the whole application.

Implements `docs/architecture/api-contract.md` §2.1–2.3 and §3.1–3.3.

## Responsibilities

- `POST /api/auth/register` — customer/professional registration (one flat JSON shape,
  `role` discriminates; professional-only fields validated conditionally in the service
  layer, not via unconditional Bean Validation). Creates the `users` row and, for
  `PROFESSIONAL`, the `professionals` row (via the `professionals` package's entity/repo)
  plus a `sos_availability` row defaulting to `isAvailable = false` (via
  `availability.repository.SosAvailabilityRepository`, added when the `V13` migration
  closed the previously-flagged schema gap — see `docs/architecture/data-model.md` §2.6).
  **As of Production Roadmap MS1, professional registration requires complete onboarding**
  — see the MS1 paragraph under Status.
- `POST /api/auth/verify` — consumes a 6-digit `verification_codes` row, sets
  `users.email_verified = true`.

  > **Temporary, closed beta:** `EMAIL_VERIFICATION_REQUIRED=false`
  > (`config/VerificationPolicy`) currently stops registration from dispatching the code at
  > all, because AWS SES is still sandboxed and rejects any recipient not individually
  > verified in the console. The endpoint, the purpose, the table and every test are
  > unchanged; nothing writes `email_verified = true` on the bypass path, so reversal is one
  > env var with no migration. See "Contact verification policy" below.
- `POST /api/auth/login` — bcrypt password check, the exact ordered lockout logic from
  `api-contract.md` §2.3 (check-lock → time-expiry reset → password check → increment/lock
  → email-verified check → success), JWT issuance on success. Every branch that throws
  after mutating the lockout counters persists that write via `LoginAttemptRecorder`
  (see "Transaction boundaries" below) rather than `UserRepository` directly, so the
  write survives the exception.
- JWT issuance/validation (`security/JwtService`), HS256, claims `sub`/`role`/`iat`/`exp`,
  24h expiry (`pronto.jwt.secret`/`pronto.jwt.expiration-seconds` in `application.yml`).
- The `SecurityFilterChain` (`config/SecurityConfig`): stateless, CSRF/form-login
  disabled, `/actuator/health` and `/api/auth/**` public, everything else authenticated.
  Also owns the `BCryptPasswordEncoder` bean. Also permits `GET /api/storage/images/**`
  (backend MS9, image retrieval via presigned/signed URL, not a JWT) and, as of MS11
  (Services & Sub-services, 2026-08-19), `GET /api/categories` (public reference data — see
  `professionals/README.md`'s MS11 section and
  `docs/architecture/product-ms11-sub-services-design.md` §3.1).
- **Guest image upload (2026-08-29)** — this package owns the guest's identity, and
  `SecurityConfig` permits the three routes that consume it: `POST /api/storage/guest-sessions`,
  `POST /api/storage/images` and `POST /api/storage/images/presigned-urls`. `permitAll()` here does
  not mean anonymous, in exactly the sense backend MS9 already established for
  `GET /api/storage/images/**`: authorization moved to the handler
  (`security.UploadOwnerResolver#requireIdentified`, `401` unless a valid JWT **or** a valid
  guest-session token was presented), because "no `Authorization` header" is now a legitimate state
  rather than a rejection. Every write that commits something — `POST /api/issues`,
  `POST /api/bookings/orders`, every `/api/sos` route — is untouched and still requires an account.
  `security.GuestSessionTokenService` and `security.UploadOwnerResolver` are the two new classes;
  see the Key classes table and `storage/README.md`'s "Guest image upload" section.
  `corsConfigurationSource()` gained `X-Pronto-Guest-Session` in its allowed-header list, without
  which the browser's preflight refuses the header and every guest upload fails before it is sent.
- `config/SecurityConfig` also owns a `corsConfigurationSource()` bean, sourced from
  `pronto.cors.allowed-origins` (env var `CORS_ALLOWED_ORIGINS`, default
  `http://localhost:5173`, now present in `application.yml` under the `pronto:` block).
  Without it, cross-origin browser requests from the Vite dev frontend were rejected with
  a `403` on the CORS preflight `OPTIONS` request before ever reaching a controller — this
  was found and fixed during Frontend Milestone 1's QA pass.
- `security/JwtAuthenticationFilter` — per-request JWT validation *and* the
  deleted-account revocation check (rejects a token whose `sub` no longer resolves to a
  non-soft-deleted `users` row), per `api-contract.md` §3.1.
- `security/JsonAuthenticationEntryPoint` — writes the standard error envelope for
  security-layer 401s (before a request ever reaches a controller/`@ControllerAdvice`).
- **Milestone 7 hardening additions** (`docs/architecture/hardening-plan.md` §5.1/§5.2):
  - `security/JwtSecretStartupGuard` — a `@PostConstruct` check (runs before the embedded
    Tomcat starts, not an `ApplicationRunner`, which was tried first but leaves a brief
    window where the server is already accepting connections — see the class's own
    Javadoc) that refuses to start (`IllegalStateException`) if `pronto.environment` (new
    config property, default `local`, set via `PRONTO_ENVIRONMENT`) is not `local` **and**
    `pronto.jwt.secret` is still the checked-in insecure placeholder. A no-op for every
    local/QA/dev startup to date (none set `PRONTO_ENVIRONMENT`).
  - `security/AuthRateLimitInterceptor` + `config/AuthWebConfig` — a simple per-client-IP,
    in-memory, fixed-window rate limiter registered on `POST /api/auth/register` (10 req /
    10 min), `.../login` (30 req / 5 min), and `.../verify` (10 req / 15 min). Closes the
    gap that per-account login lockout doesn't catch distributed credential-stuffing and
    that `/verify`'s 6-digit code had no brute-force attempt cap. `429 RATE_LIMITED` +
    `Retry-After` header on trip.
- `email/EmailSender` + `email/LoggingEmailSender` — verification code delivery, logged at
  `INFO` only. No SMTP/SES dependency added this milestone (see `api-contract.md` §3.3);
  real delivery is deferred to Milestone 5 (`notifications`), which should implement a new
  `EmailSender` behind a config flag rather than rebuilding this interface.
  **Milestone 5 update**: `EmailSender` gained a second method, `sendOrderStatusEmail(String
  toEmail, String subject, String bodyText)`, per `docs/architecture/api-contract-notifications.md`
  §4.4's decision to extend this interface rather than introduce a second,
  `notifications`-owned one. `LoggingEmailSender` implements it the same way as the original
  method (logs at `INFO`, sends nothing) — no code change to this package beyond that; the
  calling code (`notifications.scheduler.EmailDispatchJob`) and the `pronto.email.mode`
  config flag both live in `notifications`. See `notifications/README.md` for the full
  writeup.

## Key classes

| Class | Role |
|---|---|
| `controller.AuthController` | `/api/auth/register`, `/verify`, `/login`. |
| `service.AuthService` | All business logic for the three endpoints above, incl. the lockout state machine and role-specific registration validation. |
| `service.LoginAttemptRecorder` | Persists `failed_login_attempts`/`locked_until` on its own `REQUIRES_NEW` transaction — see "Transaction boundaries" below. |
| `entity.VerificationCode` | Maps `verification_codes` (owned exclusively by this package). |
| `repository.VerificationCodeRepository` | `findFirstByUserIdAndPurposeAndCodeOrderByCreatedAtDesc`. |
| `dto.*` | Request/response records for the three endpoints (`RegisterRequest/Response`, `VerifyRequest/Response`, `LoginRequest/Response`, `UserSummary`). |
| `security.JwtService` | Generates/parses JWTs. |
| `security.JwtAuthenticationFilter` | `OncePerRequestFilter`, populates `SecurityContext` from a valid Bearer token. |
| `security.JsonAuthenticationEntryPoint` | 401 envelope for unauthenticated requests to protected endpoints. |
| `config.SecurityConfig` | `SecurityFilterChain` + `PasswordEncoder` bean. |
| `security.JwtSecretStartupGuard` | (Milestone 7) Fail-fast startup check for the insecure default `pronto.jwt.secret`. |
| `security.AuthRateLimitInterceptor` | (Milestone 7) Per-IP fixed-window rate limiter, one instance per registered route. **Guest image upload (2026-08-29) added an opt-in `onlyWhenUnauthenticated` constructor**: a request carrying a verified JWT principal skips the limiter entirely. Used by exactly one registration (`storage`'s `POST /api/storage/images`) — an authenticated upload is already bounded by needing a registered, phone-verified account, and imposing a new limit on existing customers is a behaviour change this feature has no business making; an anonymous upload has no such bound and writes 8 MB objects into the uploads bucket. Every pre-existing registration (`auth`, `issues`, `users`) uses the three-argument constructor and is unaffected. |
| `security.GuestSessionTokenService` | **New, guest image upload (2026-08-29).** Mints/verifies the guest upload session — the backend-authoritative identity a visitor with no account owns their issue photos under, and the only new credential in the system. Signed with a key **derived** from `pronto.jwt.secret` (`HMAC-SHA256(secret, "pronto.guest-session.v1")`), which gives hard domain separation for free: a guest session can never verify as a user JWT and a user JWT can never verify as one of these, so no claim-inspection discipline has to hold for that to stay true. It also means no new secret to distribute — deliberate, after `STORAGE_LOCAL_HMAC_SECRET` showed what that costs a deployment recipe. Subject is a random UUID (regex-pinned on the way back in, so it can never contain `/` or `..` and be spliced into a storage key); TTL `pronto.auth.guest-session-ttl-seconds`, default 24h, matching the JWT expiry so it outlives one booking journey including registration. **What holding one lets you do, in full**: upload under `guests/{guestId}/...` through the same endpoint with the same validation and limits, re-presign a key you already own there, and — once separately authenticated — promote those keys onto your own account at issue creation. Nothing else; it is not accepted where `Authorization` is expected. Travels in `X-Pronto-Guest-Session` (deliberately not `Authorization`: the two are independently present, since a just-registered customer sends both). |
| `security.UploadOwnerResolver` | **New, guest image upload (2026-08-29).** "Given a verified JWT principal and/or a guest-session header, whose images are these?" — the single implementation, for the same reason `JwtPrincipalResolver` is the single implementation of its own question. Four routes ask it (`POST /api/storage/images`, `POST /api/storage/images/presigned-urls`, and the two `/api/issues` routes carrying `imageKeys`). `#requireIdentified` is what keeps `POST /api/storage/images` from being an open endpoint now that `SecurityConfig` no longer 401s it at the filter layer — it answers `401` unless the caller proved at least one identity. An unverifiable guest header is treated as *absent*, not as an error: a stale token in a returning visitor's `localStorage` is ordinary, not an attack, and it buys nothing either way. |
| `config.AuthWebConfig` | (Milestone 7) Registers the three `AuthRateLimitInterceptor` instances on `/api/auth/register`\|`login`\|`verify`. |
| `email.EmailSender` / `email.LoggingEmailSender` | Verification code "delivery," plus (Milestone 5) `sendOrderStatusEmail` for order-status-change email — see the Responsibilities note above. |

## Interactions with other packages

- Depends on `users` for the `User` entity/repository (reads/writes `users` rows).
- Depends on `professionals` for the `Professional` entity/repository (creates a
  professional profile at registration) and for `CategoryRepository` (validates
  `categoryId` against the `categories` reference table — see `professionals/README.md`
  for why that lookup currently lives there).
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`) and the
  `AuthenticatedUser` principal type set into the `SecurityContext`.
- `users` (`UsersController`/`UsersService`) relies on this package's `SecurityConfig` +
  `JwtAuthenticationFilter` to gate `/api/users/me`, but does not otherwise depend on
  `auth`'s internals.

## Contact verification policy

Three independent switches, each defaulting to the strict rule and each reachable only by an
operator who names it. **None is derived from `pronto.environment`** — inferring an
authentication requirement from an environment name would let "which environment am I?"
decide "is authentication complete?".

| Property (env var) | Default | Owner | What `false` removes |
|---|---|---|---|
| `pronto.auth.otp-required` (`AUTH_OTP_REQUIRED`) | `true` | `AuthOtpPolicy` | The second factor on `POST /api/auth/login`. A verified account signs in on its password alone. |
| `pronto.verification.sms-required` (`SMS_VERIFICATION_REQUIRED`) | `true` | `VerificationPolicy` | The phone half. An unproved number stops blocking issues/bookings/SOS and stops hiding a professional from the marketplace. |
| `pronto.verification.email-required` (`EMAIL_VERIFICATION_REQUIRED`) | `true` | `VerificationPolicy` | The email half. Registration dispatches no `EMAIL_VERIFICATION` code, and an unproved address stops blocking login, booking and password reset. |

All three are temporary provider-sandbox workarounds — AWS End User Messaging for the first
two, **AWS SES for the third** — and all three are set to `false` in Production today
(`infra/terraform/compute.tf`).

**The invariant that makes every one of them reversible: none writes a verification flag.**
Accounts created while a switch is off keep `email_verified = false` / `phone_verified =
false`, so the column keeps meaning "this channel was proved" and never lies. Flipping a
switch back therefore asks exactly the right accounts to prove exactly the right thing, at
their next login, through the resume-an-abandoned-registration branch that already exists.
No migration, no backfill, no code change.

**What none of them relax:** password verification, the lockout counter, the login rate
limiter, JWT issuance/validation, role authorization, every route guard, and the
soft-deleted-account check.

**`EMAIL_VERIFICATION_REQUIRED=false` is a real relaxation and is worth naming plainly:**
while it is off, an account's email address is unproved, so a typo'd address — or someone
else's — reaches the marketplace. That cost is accepted deliberately, because the
alternative under a sandboxed SES is that no real user can enter the product at all.
Note also that password reset still *delivers* over SES, so it remains unusable in the
sandbox regardless of this flag; the flag only stops `email_verified` from being the thing
that blocks it.

Tests: `config/AuthOtpPolicyTest`, `service/AuthOtpBypassTest`,
`service/EmailVerificationBypassTest`, `users/service/ContactVerificationGuardTest`. Each
asserts both settings side by side, because the risk is not "the bypass fails to work" but
"the bypass quietly became the only behaviour".

## Transaction boundaries

`AuthService.login()` is `@Transactional`. Several of its branches mutate
`user.failedLoginAttempts`/`user.lockedUntil` and then immediately throw an `ApiException`
(wrong password, lockout-threshold-reached, email-not-verified). Because `ApiException`
is an unchecked exception, Spring's default rollback policy rolls back the *entire*
transaction once it propagates out of `login()` — including any write made earlier in
that same transaction. A plain `userRepository.save(user)` right before the `throw`
therefore never actually reaches the database (this was a real, QA-caught bug: 5 wrong
passwords never escalated to a lockout because the counter write was silently discarded
every time).

The fix: `LoginAttemptRecorder.persistLockoutState(...)` is a separate `@Component`
whose method is `@Transactional(propagation = Propagation.REQUIRES_NEW)`. `login()` calls
it, synchronously, right before each throwing branch. Because it's `REQUIRES_NEW`, Spring
suspends `login()`'s transaction, runs the write in a brand-new one, and commits that new
transaction as soon as the method returns — before control goes back to `login()` and the
`throw` executes. The lockout write is therefore durable regardless of what `login()` does
afterwards.

Two things worth remembering if you add another `@Transactional` method with a similar
"write something, then conditionally throw" shape elsewhere in this codebase:

- **This only works across a real Spring bean boundary.** `REQUIRES_NEW` (or any
  `@Transactional` behavior) is implemented via a proxy around the bean. Calling
  `persistLockoutState` as a private method on `AuthService` itself — self-invocation —
  would bypass the proxy and silently run on `login()`'s own transaction, reintroducing
  the bug. That's why it's a distinct `@Component`, not a private method.
- **We deliberately did not reach for `noRollbackFor = ApiException.class` on the whole
  `login()` method.** That would have been a broader, blanket change to `login()`'s
  rollback semantics affecting every write in the method (including ones that plausibly
  *should* still roll back on an unrelated failure), for a fix that only needs to apply to
  three specific writes. The `REQUIRES_NEW` collaborator scopes the fix to exactly the
  writes that need to survive.

## Data model

Owns `verification_codes` (`docs/architecture/data-model.md` §2.3) exclusively. Writes
`users` (§2.2) and `professionals` (§2.4) rows during registration/login, via those
packages' repositories rather than raw SQL.

## Assumptions / judgment calls made during implementation

- Password policy: min 8 characters, no further complexity rule — explicitly flagged as a
  simple MVP default in `api-contract.md` §2.1, not a hard requirement from any source doc.
- Verification code TTL (15 min) and lockout duration (15 min) are implemented as `static
  final` constants in `AuthService`, not externalized to `application.yml` — no source doc
  asks for them to be configurable, and they're one-line changes if that changes later.
- `Category` (read-only reference entity for `categories`) is mapped inside
  `professionals`, not a dedicated `categories` package (none exists yet) — see that
  package's doc for the full rationale; `AuthService` depends on
  `professionals.repository.CategoryRepository` purely to validate `categoryId` at
  registration time.
- No password-reset or resend-verification-code endpoints — out of scope per
  `api-contract.md` §4, not built.

## Status

Implemented in **Milestone 1 (Auth & user management)**, per
`docs/architecture/implementation-plan.md`.

**Milestone 7 hardening pass** (`docs/architecture/hardening-plan.md` §5.1/§5.2) added the
`pronto.environment` config property + `security.JwtSecretStartupGuard` (fail-fast if a
non-local environment still uses the checked-in placeholder `JWT_SECRET`) and
`security.AuthRateLimitInterceptor` + `config.AuthWebConfig` (per-IP fixed-window rate
limiting on the three `/api/auth/*` endpoints). Both are additive — no existing endpoint
behavior, DTO shape, or config default changed; every pre-existing local/QA/dev startup
path is unaffected (neither `PRONTO_ENVIRONMENT` nor a rate-limit trip occurs under normal
usage volumes).

QA found a critical bug in `login()`'s lockout bookkeeping (writes made right before a
thrown `ApiException` were silently rolled back with the rest of the transaction — see
"Transaction boundaries" above); fixed via `LoginAttemptRecorder`. Fix was live-verified
against a real Postgres instance (`docker-compose up`): 4 consecutive wrong-password
attempts each correctly returned `401 INVALID_CREDENTIALS` with a persisted, incrementing
`failed_login_attempts`; the 5th returned `423 ACCOUNT_LOCKED` with `locked_until`
persisted; a 6th attempt with the *correct* password still returned `423` while locked;
and a successful login on an unlocked account still resets both columns and issues a JWT.
Full milestone QA sign-off is `pronto-qa`'s call, not asserted here.

**Production Roadmap MS1 — registration now requires complete onboarding, and refuses
`ADMIN` (2026-08-22).** `POST /api/auth/register` reuses the existing surface (D7) rather
than gaining a second one, but its professional payload changed in a breaking way:
`ProfessionalRegistrationData` gained two required fields, `subServiceIds` (at least one, each
proven to exist and to belong to the declared `categoryId` by
`professionals.service.SubServiceSelectionValidator` — the same component the self-service
edit endpoint calls, not a second copy of the rule) and `workingHours` (the full 7-day week,
typed as `availability.dto.WorkingHoursItemRequest` so the two surfaces cannot drift, validated
by the extracted `availability.service.WorkingHoursValidator` plus a registration-only
"at least one enabled day"). MS0 had recorded that registration wrote zero
`professional_sub_services` and zero `professional_working_hours` rows, so a brand-new
professional was listed to customers while deriving an empty calendar and the customer hit the
dead end at step 3 of 4; the fix collects the two missing pieces at the only moment the
platform has the registrant's attention, and **fabricates nothing** — `validateRoleSpecificFields`
runs every one of these checks before the `users` row is written, so a rejected submission
leaves no half-created account behind, and `persistSubServices`/`persistWorkingHours` insert
inside the same transaction without inventing a default. The `Professional` row is now created
**`PENDING`** (MS1 replaces v1.0's auto-approval outright), which is not a limbo: the
registrant can log in and edit their profile, sub-services, working hours and SOS toggle — what
they cannot do until an operator approves them is appear to customers or receive work.
Separately, `register` now **rejects `role = ADMIN` with `400 VALIDATION_ERROR` before any row
is written**: `RegisterRequest.role` is typed as `users.entity.UserRole`, so the moment MS1
added the `ADMIN` constant Jackson would bind `"ADMIN"` from a public, unauthenticated request
and the operator role that approves professionals would have been self-issuable by anyone who
can reach this endpoint. The guard is checked first and thrown immediately rather than
collected alongside other field errors — there is nothing else worth telling a caller who just
tried to make themselves an administrator. This package gained three constructor dependencies
(`SubServiceSelectionValidator`, `ProfessionalSubServiceRepository`,
`ProfessionalWorkingHoursRepository`) and no new `ErrorCode`. Unit-tested via the extended
`auth.service.AuthServiceTest`; live-validated end to end, including every negative
(both fields omitted, empty `subServiceIds`, a cross-category id → `CATEGORY_MISMATCH`, all
days disabled, six entries instead of seven, `endTime` before `startTime`, `role = ADMIN`) —
see `docs/production-roadmap/reports/MS1-report.md`, Validations 12–14. **Known limitation
carried by this package's change**: there is no documented procedure anywhere in this
repository for creating the first `ADMIN` account, precisely because registration refuses to —
recorded in the MS1 report and owned by MS7.

---

## Production MS1 (2026-08-25) — Authentication & Contact Verification

This package was restructured around one rule: **a password alone never issues a session.**

### What changed

`AuthService.login` used to return a JWT the moment BCrypt matched. It now checks the password and
issues an OTP challenge. Exactly two methods in this package construct an `AuthSession` —
`AuthService.verifyPhone` (registration completion) and `AuthService.loginOtp` — and both sit
strictly behind a successful `OtpService.redeem`. That is deliberately auditable by reading two short
methods rather than by tracing a flag.

```
register        -> account created, email code sent           (no token)
verify-email    -> email proved, phone code sent              (no token)
verify-phone    -> phone proved, registration complete        -> TOKEN

login           -> password checked, code sent to the channel
                   matching the identifier used               (no token)
login/otp       -> code redeemed                              -> TOKEN
```

### New sub-packages and classes

| Class | Responsibility |
|---|---|
| `service.OtpService` | The whole OTP lifecycle: generate, hash, dispatch, redeem, resend, invalidate. Every MS1 OTP rule is enforced here once, whichever of the nine endpoints is calling |
| `service.OtpAttemptRecorder` | Commits the failed-attempt increment on its own `REQUIRES_NEW` transaction. **Without it the attempt cap would not exist** — every wrong-code path throws, and the rollback would undo the increment |
| `service.PhoneNumberNormalizer` | Canonical E.164 via libphonenumber. Mobile-only: a number that cannot receive an SMS cannot be a second factor |
| `service.EmailNormalizer` | Lowercase + trim, and destination masking. Deliberately not provider-specific canonicalization (Gmail dots, plus-tags) |
| `entity.OtpPurpose` / `OtpChannel` | The five purposes, each carrying its channel and TTL. Constant names are the stored `verification_codes.purpose` values |
| `email.SesEmailSender` / `sms.AwsSmsSender` | Real transports, selected by `pronto.email.mode` / `pronto.sms.mode`. AWS default credential chain only — no key material in configuration or in this repository |
| `email.OtpMessageCopy` | All OTP copy, Hebrew, shared by both transports (`auth.sms` imports it) so the two cannot tell a user different stories about the same code. Owns the plain-text body, the explicitly right-to-left HTML body SES sends alongside it, and the SMS body — which is held inside one 70-character UCS-2 segment for every purpose |
| `security.ClientIpResolver` | Trusted-proxy-aware client identity for rate limiting. Honours `X-Forwarded-For` only from configured CIDRs |
| `config.ProviderModeStartupGuard` | Refuses to start with logging transports outside `local`/`test`/`demo`, and refuses the demo dataset together with real SMS |

### Things that look like they could be simplified, and must not be

- **`OtpAttemptRecorder` is a separate bean.** Spring's proxy-based `@Transactional` does not
  intercept self-invocation, so a private method on `OtpService` would silently run on the caller's
  transaction and reintroduce the bug it exists to fix. Same reasoning as the pre-existing
  `LoginAttemptRecorder`.
- **`VerificationCode.attempts`/`consumedAt`/`deliveredAt` have getters and no setters.** All three
  are advanced by conditional UPDATEs on the repository, because a read-modify-write loses the race
  that the attempt cap and the single-use rule depend on.
- **The two OTP rate rules count `deliveredAt`, not `createdAt`** (`V54`). They are not the same
  number, and the difference is a bug that reached customers. `created_at` is written when the
  challenge row is inserted, which is *before* the SES/SNS call; a dispatch that then fails is
  abandoned and the API answers `OTP_DELIVERY_FAILED`, whose entire meaning is that nothing changed.
  Counting that row anyway meant **one** provider refusal blocked the user's next resend for 60
  seconds — right after the UI told them to try again — and **five** exhausted the hourly ceiling and
  locked them out of verification for an hour without a single message having been sent. Neither rule
  was relaxed for messages that *were* delivered: 60s of spacing and 5 codes per purpose per hour
  still hold, and they are what bounds the SMS bill and protects whoever owns the handset. A failed
  send reaches nobody and costs nothing, so charging a customer's budget for it protected nothing.
  Request volume from one source stays bounded by `security.AuthRateLimitInterceptor`
  (10 per 15 minutes on `/api/auth/otp/resend`), which is untouched.
- **`OtpService.dispatch` catches provider failures and returns `delivered=false`** instead of
  throwing. The right response differs per flow: registration rolls back on a failed dispatch, email
  verification must not (the user really did prove their email), and password reset must report
  success regardless or it becomes an existence oracle.
- **The order of the two matchers in `SecurityConfig`.** `/api/auth/phone/capture` is matched
  `.authenticated()` *before* the `permitAll` on `/api/auth/**`; Spring Security's first match wins,
  so reversing the lines would silently open it.

### Related documentation

`docs/production-roadmap/reports/prod-MS1-report.md` (full rationale, security review, known
limitations) · `docs/architecture/api-contract.md` "Production MS1" section · `V46`, `V47`, `V48`,
`V54`.

## Production MS4 (2026-08-26) — Production Security & Configuration

Three changes in this package, all of them about configuration that could previously reach
Production unnoticed.

### `config.CorsOriginStartupGuard` (new)

`pronto.cors.allowed-origins` defaults to `http://localhost:5173` — correct for the only
environment that existed when it was written, a deployment defect anywhere else. A Production
instance that forgot `CORS_ALLOWED_ORIGINS` had an API whose sole permitted browser origin was a
developer's laptop, and neither symptom ("the frontend is broken", "the allow-list names a laptop")
points at CORS from the outside.

Four rules, production-like environments only:

| Rule | Why |
|---|---|
| not empty | `CORS_ALLOWED_ORIGINS=""` rejects every cross-origin call with no diagnostic anywhere |
| no wildcard | `CorsConfiguration` accepts `*` silently. Blast radius is bounded today — `SecurityConfig` leaves `allowCredentials` false and the JWT travels in `Authorization`, not a cookie — but it is a permission nobody decided to grant, and it becomes the whole vulnerability the moment a cookie appears |
| no `localhost`/`127.0.0.1`/`::1`/`0.0.0.0` | this is what an unset variable looks like |
| `https` only | every JWT this API issues travels from that origin |

### `security.JwtSecretStartupGuard` — extended

Previously detected only the exact checked-in placeholder, so `JWT_SECRET=hunter2` passed it and
then died inside jjwt's `Keys.hmacShaKeyFor` with a `WeakKeyException` naming no environment
variable. Now also refuses an empty secret (the shape of a half-populated secret injection, where
the variable exists so the YAML default never applies) and anything under 32 characters. The value
itself never appears in the message. Its rule stays the stricter `!= local`, **not**
`ProntoEnvironment.isProductionLike()` — see `common.config.ProntoEnvironment`'s Javadoc for why
loosening it would be a regression. First test coverage: `security/JwtSecretStartupGuardTest`.

### `config.ProductionHardeningStartupGuard` — `TRUSTED_PROXIES` value validation

The MS1 guard checked that `TRUSTED_PROXIES` was *non-empty*. It never checked what was in it, so
`TRUSTED_PROXIES=0.0.0.0/0` passed — and that value makes **every client a trusted proxy**, so every
client's `X-Forwarded-For` is believed: the auth rate limiter is bypassable with one header, and any
victim's bucket can be spent by naming their address. A pasted set of AWS's published public ranges
does the same thing, which `application.yml` warned about and nothing enforced.

Every configured block must now lie inside **private address space** (RFC 1918, loopback,
link-local, RFC 6598 CGNAT, IPv6 ULA/link-local/loopback). The test is deliberately containment and
not a prefix-width floor: what matters is not whether a block is narrow but whether a stranger's
packet can arrive with a source address inside it. `10.0.0.0/8` is wide and perfectly safe — no
public source address is ever in it — while a single public `/24` is not. Validated whether or not
`behind-proxy` is set, because `security.ClientIpResolver` acts on the list and never consults
`behind-proxy`.

### `security.CidrBlock` (extracted)

Was a private record inside `ClientIpResolver`, the right size while exactly one class needed it.
The guard above is a second consumer, and the two **must** agree byte for byte on what a CIDR string
means — a guard with a subtly different parser would be worse than no guard, because it would
approve a configuration whose real behaviour it had never examined. So there is one parser now, used
by both. Extraction also fixed a real bug in `isIpLiteral`: it decided whether hex letters were legal
by whether a colon had been seen *so far*, left to right, which rejected every IPv6 literal starting
with a hex digit (`fc00::/7`, `fe80::1`). Fail-closed in `ClientIpResolver` — such an address was
simply never trusted — but it is now correct.

### `email.LoggingEmailSender` — order-status logging fenced

`sendOrderStatusEmail` logged the recipient address and the entire message body in every
environment; it was the one path in that class not behind the environment check. Unreachable in
production (`ProviderModeStartupGuard` refuses this bean as the transport there), but it was
personal data logged unconditionally, and the fence costs one boolean.

### `config.ProviderModeStartupGuard` — unrecognized modes

`EMAIL_MODE`/`SMS_MODE` outside their known sets are now refused with a message naming the variable,
instead of failing later with a `NoSuchBeanDefinitionException` naming an interface.

Tests: `config/CorsOriginStartupGuardTest`, `security/JwtSecretStartupGuardTest`, extended
`config/ProductionHardeningStartupGuardTest` and `config/ProviderModeStartupGuardTest`, plus the
cross-package `common/config/ProductionStartupValidationTest`.

---

## Production MS5 — deployment

### `config.SecurityConfig` — `/actuator/health` widened to `/actuator/health/**`

Spring Security's path matcher treats `"/actuator/health"` as **exactly** that string. MS5 adds the
liveness and readiness groups, which live at `/actuator/health/liveness` and
`/actuator/health/readiness` — sub-paths that fell through to the authenticated catch-all and
answered **401**. An ALB target health check receiving 401 marks every task unhealthy and drains the
service to zero, so this would have presented as a total outage on the first deploy, with an
application log that said nothing at all.

Widening leaks nothing further: `management.endpoints.web.exposure.include` is still `health` alone,
so `/actuator/**` resolves to nothing but health and its groups, and `show-details: when-authorized`
still withholds per-indicator detail from an unauthenticated caller.

### `security.AuthRateLimitInterceptor` — the refusal log

One `WARN` on refusal: `pronto.ratelimit.refused client=… route=… count=… limit=… retryAfterSeconds=…`

This is what makes `TRUSTED_PROXIES` **verifiable in production**. Before it, the only observable
symptom of a wrong value was the 429 itself — identical in the healthy case and the broken one, so
the deployment could only be validated by inference. Two public addresses driven to a refusal must
now produce two different `client` values, and a forged `X-Forwarded-For` must produce the sender's
real address: a direct read of `ClientIpResolver`'s decision, which nothing else exposes.

Deliberately logged: the resolved key, the fixed route, the count, the retry-after. Deliberately not:
the `Authorization` header, the query string, the body — this interceptor sits on login and
registration, so the request it refuses is routinely carrying a password. Asserted by test.

The event key is in the same stable, greppable form as `openai.request.failed`, because a CloudWatch
metric filter keys on it; renaming it would silently zero an alarm.

### `security.ClientIpResolver` — unchanged, and `forward-headers-strategy` must stay unset

No change was needed here. What MS5 adds is the deployment-side half: `TRUSTED_PROXIES` is now
**generated from the VPC** (`infra/terraform/outputs.tf`) rather than hand-written, and the ECS task's
security group admits port 8080 from the ALB's security group alone — which closes the HIGH-severity
risk MS4 recorded as not enforceable in application code.

`server.forward-headers-strategy` is left at Spring Boot's default of `NONE`, permanently. Setting it
installs Tomcat's `RemoteIpValve`, which rewrites `getRemoteAddr()` from the very header this
resolver is deciding whether to trust — the peer becomes the client's public address, matches no
private block, and the resolver returns the unverified attacker-supplied value for every request,
while every startup guard still passes.

Tests: extended `security/AuthRateLimitInterceptorTest` (3 MS5 cases), and
`common/config/HealthProbeIntegrationTest` for the matcher.

---

## Contact availability (`POST /api/auth/availability`)

`ContactAvailabilityService` answers one question about one value: **would `POST /api/auth/register`
accept this email address / phone number?** It exists so a registration form can put "already
registered" under the field the customer is looking at, instead of on the confirmation screen after
they have chosen a password and reviewed a summary.

### Why an endpoint this package's own rules seem to forbid

Everything else here works to avoid becoming an account-existence oracle: `requestPasswordReset`
answers identically whether or not the account exists, `verifyPassword` burns a BCrypt hash on the
unknown-identifier branch so response time does not betray it, `EmailNormalizer.mask` masks what was
*submitted* rather than what is stored. A "does this email exist" endpoint is the opposite of all of
that, and its absence was not an oversight.

**What makes it acceptable is that the oracle already exists, one door along.** `POST
/api/auth/register` answers `409 DUPLICATE_EMAIL` and `409 DUPLICATE_PHONE` as distinct codes — it
has to, or no form can highlight the right field — so anyone willing to send a registration attempt
can already ask this exact question and get this exact answer. This endpoint does not create the
disclosure; it makes it **cheaper**. The whole security question is therefore *how much cheaper*, and
that is a rate-limit question:

| route | budget per client | cost per probe |
|---|---|---|
| `POST /api/auth/register` | 10 / 10 min | BCrypt hash + insert + email |
| `POST /api/auth/availability` | 20 / 10 min | one indexed lookup |

Twice the bandwidth, and the client key comes from `ClientIpResolver` rather than from a spoofable
header. 20 guesses per source per 10 minutes is not a viable way to enumerate an email namespace.
That threshold is a security budget, not headroom — see the table in `config.AuthWebConfig` before
raising it.

### What it discloses, and what it refuses to

The response is `{field, available}` and nothing else. No account id, role, name, masked value,
created-at, verified flag or deleted flag — and `available = false` does **not** distinguish an
active account from one that never finished verifying from one that has been soft deleted, because
the question asked is exactly `createAccount`'s `existsByEmail`/`existsByPhone` and no more. Two
consequences, both intended: the answer cannot drift from what registration would actually do, and
it carries no account state. A test asserts the record has two components, so a third has to be
argued for.

Nothing logs the submitted value.

### It is advisory, never permission

`AuthAccountWriter.createAccount` is unchanged: it still performs its own pre-insert duplicate
checks and still relies on `ux_users_email` / `ux_users_phone` to settle the race those cannot. The
availability answer is true when given and can be false a minute later while the customer picks a
password, so the frontend still handles `DUPLICATE_EMAIL`/`DUPLICATE_PHONE` at submit — that
handling is not redundant, it is the race.

### Normalization, and why the rules are borrowed rather than copied

The submitted value is canonicalized by the same two components registration uses, so
`Taken@Example.COM ` and `050-223-4567` cannot report available and then lose to a unique index.
Phone shape is decided by `PhoneNumberNormalizer` (libphonenumber), which is also why a landline
comes back `400 VALIDATION_ERROR` here — the customer learns on the field, on blur, that the number
cannot receive an SMS. Email shape is checked with
`validator.validateValue(RegisterRequest.class, "email", …)`, i.e. against `RegisterRequest`'s own
declared constraint rather than a second copy of it, so the two cannot disagree.

A malformed value is a `400`, not `available: true`: answering "available" for something that is not
an address would march the customer on to a registration that is already doomed.

Tests: `service/ContactAvailabilityServiceTest` (12 cases).

---

## The OTP master switch (`OTP_VERIFICATION_ENABLED`)

**Current state: OTP verification is OFF, deliberately, including in production.** This is a
temporary product decision for the feedback/beta phase. The plan is to clear the database and turn
it back on before real customers are onboarded.

### One variable, gating three

Three flags already existed, each added for a different provider outage and each still the right
granularity for its own problem:

| property | env | what it governs |
|---|---|---|
| `pronto.verification.email-required` | `EMAIL_VERIFICATION_REQUIRED` | registration's email code |
| `pronto.verification.sms-required` | `SMS_VERIFICATION_REQUIRED` | registration's phone code |
| `pronto.auth.otp-required` | `AUTH_OTP_REQUIRED` | login's second factor |

What none of them expressed is the product decision "we are not doing OTP at all", which needed
three variables set correctly and produced a silently half-verified deployment if an operator set
two. `OtpVerificationPolicy` does not replace them — it **gates** them. Both policy beans resolve
their answer as `master && ownFlag` at construction, which means every existing consumer reads
exactly the policy it already read, no call site had to learn about the new class, and turning OTP
back on restores whatever the sub-flags say.

The sub-flag is still parsed before the master is consulted, so `AUTH_OTP_REQUIRED=flase` refuses
the boot even while OTP is off — the worst time to discover that typo is the day verification comes
back on.

### With `OTP_VERIFICATION_ENABLED=false`

`POST /api/auth/register` → validate → create the account → **`AUTHENTICATED` with a session**. No
`verification_codes` row is written, no code is generated, neither SES nor SNS is called on any auth
path, `OTP_DELIVERY_FAILED` is unreachable, and no account is left pending. `POST /api/auth/login`
completes on the password. `ContactVerificationGuard` and `ProfessionalEligibility` stop asking for
proof nobody was given a chance to provide.

**Nothing is written to the database.** Accounts created in this state keep `email_verified = false`
and `phone_verified = false`. The columns keep meaning "this channel was proved"; whether an
unproved channel *blocks* is what the policy decides. Writing `true` into them is the one change in
this feature that would outlive the flag — it would permanently record unproved addresses as proved,
and re-enabling would silently grandfather the whole beta cohort instead of asking them to verify.

**Everything else still applies**: email format, phone format and SMS-reachability
(`PhoneNumberNormalizer` — a landline is still refused), duplicate email, duplicate phone, password
length, every required field, BCrypt verification, the lockout counter, every per-IP rate limiter,
JWT issuance/validation, and every role and route guard. This is not anonymous access.

### The two contradictions that had to be fixed

Switching the policy off is not sufficient on its own; two places read the raw columns and would
have produced states that contradict "the user is fully usable".

1. **Phone login.** `AuthAccountWriter#resolveIdentifier` filtered on `phone_verified`, which no
   account can ever reach while verification is off — so *every* beta user would have been silently
   unable to sign in with the number they registered with, and told "invalid credentials" for a
   correct password. The filter is now conditional on `VerificationPolicy`. The accepted cost is
   stated in that method's Javadoc: while the policy is off, an unproved number can *name* an
   account. It is not a way into one — the password is still verified — and the strict rule returns
   with no migration.
2. **The phone-capture screen.** `GET /api/users/me` now returns `phoneVerificationRequired`
   alongside `phoneVerified`, so a client can tell "unproved and being asked" from "unproved and
   nobody is asking". `phoneVerified` was deliberately *not* made to report `true` under the
   policy — see `UserMeResponse`. `AuthService#capturePhone` refuses outright while the policy is
   off rather than dispatching a code nothing will redeem.

Everything else was already policy-conditional and was verified by walking every `isEmailVerified()`
/ `isPhoneVerified()` call site in `src/main`.

### Production is a supported place to run this

There is deliberately **no** startup guard forbidding `false` in a production-like environment — the
beta runs with OTP off in production by decision. The state is made loud instead: `WARN` on every
boot from `OtpVerificationPolicy`, alongside the two existing policy announcements.

One existing guard was relaxed, narrowly. `ProviderModeStartupGuard` refused `SMS_MODE=log` in
production because undelivered codes leave accounts unreachable; with OTP off, no code is generated,
so that reasoning is void and the guard would only force an operator to hold real AWS End User
Messaging credentials for a subsystem that is switched off. Safe because `SmsSender` has exactly one
consumer, `OtpService`. **`EMAIL_MODE=log` stays refused unconditionally** — `EmailSender` is also
used by `notifications.scheduler.EmailDispatchJob` for order-status mail, which is not OTP and is
still expected to be delivered.

### Re-enabling

`OTP_VERIFICATION_ENABLED=true`. Nothing else: no migration, no backfill, no code change. Confirm
`EMAIL_MODE=ses` / `SMS_MODE=aws` and that delivery actually works first — turning the requirement
back on while delivery is broken recreates the trap these settings exist to escape.

Tests: `service/OtpVerificationDisabledTest` (16), `config/OtpVerificationPolicyTest` (23), plus the
two new `ProviderModeStartupGuardTest` cases and three in `users/service/UsersServiceTest`.
