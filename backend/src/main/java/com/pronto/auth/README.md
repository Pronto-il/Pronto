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
| `security.AuthRateLimitInterceptor` | (Milestone 7) Per-IP fixed-window rate limiter, one instance per registered route. |
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
- **`VerificationCode.attempts`/`consumedAt` have getters and no setters.** Both are advanced by
  conditional UPDATEs on the repository, because a read-modify-write loses the race that the attempt
  cap and the single-use rule depend on.
- **`OtpService.dispatch` catches provider failures and returns `delivered=false`** instead of
  throwing. The right response differs per flow: registration rolls back on a failed dispatch, email
  verification must not (the user really did prove their email), and password reset must report
  success regardless or it becomes an existence oracle.
- **The order of the two matchers in `SecurityConfig`.** `/api/auth/phone/capture` is matched
  `.authenticated()` *before* the `permitAll` on `/api/auth/**`; Spring Security's first match wins,
  so reversing the lines would silently open it.

### Related documentation

`docs/production-roadmap/reports/prod-MS1-report.md` (full rationale, security review, known
limitations) · `docs/architecture/api-contract.md` "Production MS1" section · `V46`, `V47`, `V48`.

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
