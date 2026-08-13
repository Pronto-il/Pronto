# `auth`

## Purpose

Registration, login, email verification codes, password hashing, account lockout, JWT
issuance/validation, and Spring Security wiring for the whole application.

Implements `docs/architecture/api-contract.md` §2.1–2.3 and §3.1–3.3.

## Responsibilities

- `POST /api/auth/register` — customer/professional registration (one flat JSON shape,
  `role` discriminates; professional-only fields validated conditionally in the service
  layer, not via unconditional Bean Validation). Creates the `users` row and, for
  `PROFESSIONAL`, the `professionals` row (via the `professionals` package's entity/repo).
  Does **not** create an `sos_availability` row — that table doesn't exist yet (flagged
  gap, see `docs/architecture/data-model.md` §4 and `api-contract.md` §4).
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
  Also owns the `BCryptPasswordEncoder` bean.
- `security/JwtAuthenticationFilter` — per-request JWT validation *and* the
  deleted-account revocation check (rejects a token whose `sub` no longer resolves to a
  non-soft-deleted `users` row), per `api-contract.md` §3.1.
- `security/JsonAuthenticationEntryPoint` — writes the standard error envelope for
  security-layer 401s (before a request ever reaches a controller/`@ControllerAdvice`).
- `email/EmailSender` + `email/LoggingEmailSender` — verification code delivery, logged at
  `INFO` only. No SMTP/SES dependency added this milestone (see `api-contract.md` §3.3);
  real delivery is deferred to Milestone 5 (`notifications`), which should implement a new
  `EmailSender` behind a config flag rather than rebuilding this interface.

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
| `email.EmailSender` / `email.LoggingEmailSender` | Verification code "delivery." |

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

QA found a critical bug in `login()`'s lockout bookkeeping (writes made right before a
thrown `ApiException` were silently rolled back with the rest of the transaction — see
"Transaction boundaries" above); fixed via `LoginAttemptRecorder`. Fix was live-verified
against a real Postgres instance (`docker-compose up`): 4 consecutive wrong-password
attempts each correctly returned `401 INVALID_CREDENTIALS` with a persisted, incrementing
`failed_login_attempts`; the 5th returned `423 ACCOUNT_LOCKED` with `locked_until`
persisted; a 6th attempt with the *correct* password still returned `423` while locked;
and a successful login on an unlocked account still resets both columns and issues a JWT.
Full milestone QA sign-off is `pronto-qa`'s call, not asserted here.
