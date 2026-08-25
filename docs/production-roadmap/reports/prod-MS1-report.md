# Production MS1 — Authentication & Contact Verification

**Status: `DONE`** — final Gate review passed 2026-08-25. Implementation, automated validation and
local end-to-end validation are complete, and **both AWS-dependent Gate items have been performed
against a live account and passed**: real SES email to a real inbox, and real SMS to a real Israeli
`+972` handset with sender ID `PRONTO`. See Part 3 (§H) and Part 4.

What remains is **not** a code item and does not hold the implementation milestone open: the AWS
account is still in the **SMS sandbox**, so delivery today is limited to destination numbers verified
in the console. Those, and `TRUSTED_PROXIES`, are Production operational prerequisites — Part 4 §O.
`DONE` is the MS1 implementation gate; it is **not** a claim that Pronto is Production ready. MS2–MS7
remain outstanding and MS2 has not been started.

> **This report has four parts.** Part 1 (§1-§15) records the original MS1 implementation. Part 2
> records the pre-DONE security and architecture audit and the eleven fixes it produced — including
> three HIGH findings. Part 3 records live-provider validation and the OTP copy / Hebrew-RTL cleanup
> that followed it. **Part 4, at the end, is the final Gate review and the `DONE` decision.** Where
> they disagree, the later part is current: Part 1's "not validated" statements about SES and SMS are
> superseded by Part 3, Part 1's and Part 2's OTP message copy is superseded by Part 3 §J, and every
> `PARTIAL` status statement in Parts 1-3 is superseded by Part 4.

| | |
|---|---|
| Governing roadmap | `Pronto_1.0_Production_Roadmap.md` (7-milestone Production roadmap) |
| Baseline audited | `main` @ `8a0ef87de45b1c37f2f8faf5d1bbd9d432f2c1e5` |
| Branch | `ms1-auth-contact-verification` (not committed, not pushed, not merged) |
| Migrations added | `V46`, `V47`, `V48` |
| Backend tests | **990 pass**, 1 skipped (pre-existing OpenAI eval runner) — 158 added in Part 1, 46 in Part 2, 47 in Part 3 |
| Frontend | `tsc -b` clean, `oxlint` clean (3 pre-existing warnings), production build succeeds |

---

## 1. Scope

Make registration, login, email ownership and phone ownership Production-ready:

- every account has a verified email **and** a verified phone;
- a password alone never issues a session;
- either identifier — email or phone — logs into the same account;
- OTPs are cryptographically generated, hashed at rest, single-use, attempt-capped, resendable
  under a cooldown, and never logged outside a developer machine;
- real Email and SMS providers exist behind configuration, and a Production-like environment cannot
  start with fake ones;
- password recovery exists and does not reveal who has an account;
- rate limiting attributes requests correctly behind an AWS load balancer;
- accounts created before this milestone keep working.

Out of scope and untouched: professional approval lifecycle, marketplace eligibility rules (other
than adding phone verification to the predicate), SOS lifecycle, service coverage, polling and
notification efficiency.

## 2. Baseline audited

Verified directly against the code at `8a0ef87`, not from prior reports. What was there:

| Area | State at baseline |
|---|---|
| Email | Stored as typed; unique via `ux_users_email_lower`; looked up with `findByEmailIgnoreCase` → `upper(email) = upper(?)`, which no index covered (sequential scan on every login) |
| Phone | `users.phone VARCHAR(20)`, nullable, free text, **CUSTOMER-only**, **not unique**, no format rule, never verified |
| Second factor | **None.** `AuthService.login` returned a JWT the moment BCrypt matched |
| OTP storage | `verification_codes.code VARCHAR(10)` — **plaintext** |
| OTP logging | `LoggingEmailSender` logged recipient + code at `INFO`, and was the only email path in the system |
| Resend | **No endpoint.** Expired-code copy told the user to re-register |
| Attempt cap | None per code; only a per-IP request-volume limiter |
| Recovery | **None.** Zero occurrences of password reset repo-wide |
| Providers | `EmailSender` interface with one log-only implementation; `pronto.email.mode` present in `application.yml` and **read by no code**; no SMS abstraction of any kind |
| Rate limiting | In-memory, `getRemoteAddr()` only, no eviction, 3 of the auth routes covered |
| Duplicate-email race | `DataIntegrityViolationException` had no handler → `500 INTERNAL_ERROR` |

## 3. Decisions

Approved by the product owner before implementation:

1. **SMS provider: AWS.** AWS End User Messaging SMS, published via `SNS::Publish`, behind a generic
   `SmsSender`. Standard credential chain / IAM roles only; no key material in configuration or in
   this repository. Non-local environments must never silently fall back to a logging sender.
2. **Registration completion issues a JWT immediately** once both channels are proved — no second
   login and no second OTP straight after.
3. **No Production user base to preserve.** V46/V48 normalization approved, with the constraint that
   migrations stay deterministic, never fabricate phone numbers, null what cannot be canonicalized,
   and abort loudly on unexpected email collisions rather than merging accounts. Demo/test users must
   remain usable.
4. **Report path** `docs/production-roadmap/reports/prod-MS1-report.md`; the old
   professional-verification `MS1-report.md` is untouched.

### Decisions taken during implementation (and why)

- **Which environments may run logging transports.** The brief said "non-local". Implemented as
  "not one of `local` / `test` / `demo`", because `demo` and `test` are already first-class
  non-production environments in this codebase (`DemoDataStartupGuard` recognizes exactly those
  three names), the TEST/DEMO dataset runs on synthetic phone numbers, and requiring real SES/SNS
  there would either break the demo environment or start sending real SMS to numbers that belong to
  strangers. An unrecognized environment name is treated as production, so the exemption cannot be
  reached by accident. `JwtSecretStartupGuard` keeps its stricter `!= local` rule — a
  publicly-known signing key is directly exploitable wherever it runs, which a log-only mail
  transport is not.
- **A demo/real-SMS interlock was added.** `ProviderModeStartupGuard` refuses to start with the demo
  dataset enabled and `SMS_MODE=aws`: the demo accounts' phone numbers are invented and may belong
  to real people.
- **Registration rolls back on a failed email dispatch; email verification does not.** See §4.
- **No `destination` column on `verification_codes`.** The audit proposed one; it is derivable from
  `(user_id, purpose)`, and a second stored copy of a user's contact details is PII duplication with
  no benefit.
- **Testcontainers was tried and dropped** for the DB integration tests. It could not reach Docker
  Desktop's named pipe on this machine, and it would have added a dependency and a Docker requirement
  to get a PostgreSQL server this project already has one of in every environment. The tests use the
  project's existing `DB_*` variables and create/drop their own scratch database.

## 4. Architecture implemented

### Flows

```
register        -> account created, email code sent           (no token)
verify-email    -> email proved, phone code sent              (no token)
verify-phone    -> phone proved, registration complete        -> TOKEN

login           -> password checked, code sent to the channel
                   matching the identifier used               (no token)
login/otp       -> code redeemed                              -> TOKEN
```

Exactly two methods construct an `AuthSession` — `AuthService.verifyPhone` and
`AuthService.loginOtp` — and both sit strictly behind a successful `OtpService.redeem`. That is the
structural form of the milestone's central rule, and it is deliberately auditable by reading two
short methods.

A correct password on an account that never verified its email returns a `VERIFY_EMAIL` challenge
rather than a login challenge, so an abandoned registration is resumed by simply logging in — no
separate "I never got my code" flow to build or to abuse.

### Identifier resolution

`identifier` is normalized through libphonenumber first; anything that is not a valid, assignable
mobile number is treated as an email address. **Phone resolution additionally requires
`phone_verified`** — a number that was merely typed into a form is contact detail, not a credential.
Both paths return the same `users` row.

### OTP lifecycle (`OtpService`)

| Rule | Implementation |
|---|---|
| 6 digits, cryptographically secure | `SecureRandom.nextInt(1_000_000)` formatted `%06d` (leading zeros kept — a naive `toString` would shrink the keyspace tenfold for one code in ten) |
| No plaintext at rest | SHA-256 hex only. A slow KDF is deliberately not used: the secret is a 6-digit number with a ≤15-minute life and a hard 5-attempt cap, so the brute-force surface is bounded by the cap, not by hash cost |
| Single use | Conditional `UPDATE ... SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL`; the row count decides the winner, so two requests carrying the same correct code cannot both succeed |
| 5 attempts per challenge | Conditional `UPDATE ... SET attempts = attempts + 1 WHERE attempts < :max`, committed on its own transaction (`OtpAttemptRecorder`, `REQUIRES_NEW`). **Without the separate transaction the cap would not exist** — every wrong-code path throws, and the rollback would undo the increment |
| TTL | 10 min login, 15 min contact verification and password reset |
| Resend replaces | Issuing invalidates every open code of the same purpose, so resending narrows the window instead of widening it |
| Cooldown / volume | 60 s between deliberate resends; 5 codes per purpose per user per rolling hour |
| Never logged | Transports log the code only when `pronto.environment=local`; every other environment logs purpose only, with no code and no destination |

### Constraints as the real rule

`ux_users_email` and `ux_users_phone` are the enforcement; the pre-insert checks are a courtesy that
produces a better field error in the common case. Losing that race now returns `409 DUPLICATE_EMAIL`
/ `409 DUPLICATE_PHONE` instead of `500`.

### Provider abstractions

`EmailSender` → `LoggingEmailSender` (`log`) / `SesEmailSender` (`ses`).
`SmsSender` → `LoggingSmsSender` (`log`) / `AwsSmsSender` (`aws`).
Selected by `@ConditionalOnProperty`; AWS SDK default credential chain only; per-call timeouts set
explicitly because both sends sit inline on request paths.

### Failure behaviour

`OtpService.dispatch` catches provider failures and reports `delivered = false` rather than
throwing, because the correct response differs per flow:

- **registration** — a failed dispatch throws `OTP_DELIVERY_FAILED` and the whole transaction rolls
  back. An account that exists, cannot be verified, and permanently occupies its owner's email and
  phone in two unique indexes is worse than no account; a rollback lets the registrant simply try
  again.
- **email verification** — a failed *phone* dispatch does **not** undo the email verification. The
  user genuinely proved their email; taking that back over an SMS-gateway hiccup would make them
  redeem the same code twice. The response carries `delivered = false` and the UI leads with
  "resend".
- **password reset** — always reports `delivered = true`, because "delivery failed" is only
  answerable for an address that exists.

### Legacy accounts

`ContactVerificationGuard` refuses `PHONE_VERIFICATION_REQUIRED` on issue creation, order creation
and SOS activation. Professional marketplace eligibility is enforced differently and deliberately:
phone verification is folded into `ProfessionalEligibility.ELIGIBLE_JPQL`, so an unverified
professional is simply not discoverable across all six gated paths rather than being checked in six
places, one of which a future consumer would forget.

### Rate limiting behind AWS

`ClientIpResolver` honours `X-Forwarded-For` **only** when the direct peer is inside a configured
trusted CIDR, then walks the chain from the right, skipping trusted hops. With
`TRUSTED_PROXIES` empty — the default, and every local/CI run — it returns the peer address and
ignores the header entirely. Coverage extended from 3 routes to all 9, and the counter table now
evicts expired windows (it previously grew for the life of the process, one entry per address ever
seen, which an attacker can drive deliberately).

## 5. Files changed

93 files. Highlights:

**Backend — new (21):** `auth/entity/{OtpPurpose,OtpChannel}`, `auth/service/{OtpService,
OtpAttemptRecorder,PhoneNumberNormalizer,EmailNormalizer}`, `auth/email/{OtpMessageCopy,
SesEmailSender}`, `auth/sms/{SmsSender,LoggingSmsSender,AwsSmsSender}`,
`auth/security/ClientIpResolver`, `auth/config/ProviderModeStartupGuard`,
`common/config/ProntoEnvironment`, `users/service/ContactVerificationGuard`, 7 DTOs.

**Backend — modified (29):** `AuthService` (restructured), `AuthController` (3 endpoints → 9),
`VerificationCode`, `VerificationCodeRepository`, `User`, `UserRepository`, `UsersService`,
`AuthRateLimitInterceptor`, `AuthWebConfig`, `SecurityConfig`, `ErrorCode`,
`GlobalExceptionHandler`, `ProfessionalEligibility`, `IssuesService`, `BookingsService`,
`SosService`, `DemoDatasetWriter`, `application.yml`, `pom.xml`.

**Backend — deleted (4):** `LoginResponse`, `RegisterResponse`, `VerifyRequest`, `VerifyResponse`.

**Backend — tests:** 7 new classes (`OtpServiceTest`, `AuthFlowTest`, `PhoneNumberNormalizerTest`,
`ClientIpResolverTest`, `AuthRateLimitInterceptorTest`, `ProviderModeStartupGuardTest`,
`OtpLoggingTest`, `ContactVerificationGuardTest`, `MigrationIntegrationTest`, plus the
`InMemoryVerificationCodes` fake); 7 existing classes adapted.

**Frontend — new (4):** `OtpForm`, `AuthChallengePage`, `PasswordResetPage`, `PhoneCapturePage`.
**Frontend — modified (11):** `shared/api/{auth,users,httpClient,index}`,
`shared/hooks/{authContext,AuthProvider}`, `features/auth/{LoginForm,LoginPage,
CustomerRegisterForm,CustomerRegisterPage,ProfessionalRegisterForm,ProfessionalRegisterPage,index}`,
`app/{router,AppLayout}`.
**Frontend — deleted (2):** `VerifyPage`, `VerifyCodeForm`.

## 6. DB migrations

Forward-only. No applied migration was edited.

**`V46__alter_users_phone_identity.sql`** — `phone_verified BOOLEAN NOT NULL DEFAULT false`;
canonicalize existing `phone` to E.164 for the three accepted Israeli spellings; NULL anything not
canonicalizable; resolve duplicates in favour of the **oldest** row (lowest id) and NULL later
claimants; `ck_users_phone_e164`; `ux_users_phone`.

`phone` stays **nullable**, deliberately — that nullability *is* the legacy cohort. A `NOT NULL`
would have meant inventing phone numbers.

**`V47__harden_verification_codes.sql`** — add `code_hash`, `challenge_id`, `attempts`; delete
outstanding (un-consumed) plaintext codes, which cannot be migrated because their hash is unknowable
and which had ≤15 minutes of life left; drop the `code` column; widen the purpose CHECK to the five
MS1 purposes; unique index on `challenge_id`; composite index for the resend/cooldown reads.

**`V48__normalize_users_email.sql`** — abort with a plpgsql `RAISE` if any two rows would collide
after normalization; lowercase + trim; create `ux_users_email`; drop `ux_users_email_lower`.

### Deviations from the audited proposal

| Proposed | Delivered | Why |
|---|---|---|
| `destination VARCHAR(255)` on `verification_codes` | Not added | Derivable from `(user_id, purpose)`; a second copy of contact details is PII duplication |
| Partial unique index on phone | Total unique index | Matches how email has always been treated. `UsersService.deleteMe` now nulls the phone (as it already rewrote the email), which is what actually releases an identifier — an explicit act, not a side effect of a tombstone |
| — | `V47` also deletes outstanding codes | Not in the proposal; unavoidable, since a one-way hash cannot be computed backwards |

Applied and verified against the real local development database (95 users): 3 migrations, no data
loss, 4 phone numbers canonicalized, 0 accounts grandfathered into a verified phone.

## 7. API changes

**Breaking.** Every response shape below changed; the frontend in this branch matches.

| Endpoint | Change |
|---|---|
| `POST /api/auth/register` | `phone` now top-level and **required for both roles** (was `customer.phone`, customer-only). Returns `AuthStepResponse`, not `RegisterResponse` |
| `POST /api/auth/verify` | **Removed** — replaced by the two below |
| `POST /api/auth/verify-email` | New. `{challengeId, code}` |
| `POST /api/auth/verify-phone` | New. `{challengeId, code}` → **issues a session** |
| `POST /api/auth/login` | `{identifier, password}` (was `{email, password}`). Returns a challenge; **never a token** |
| `POST /api/auth/login/otp` | New. `{challengeId, code}` → **issues a session** |
| `POST /api/auth/otp/resend` | New. `{challengeId}` |
| `POST /api/auth/phone/capture` | New. **Authenticated.** `{phone}` |
| `POST /api/auth/password-reset/request` | New. `{identifier}` → always a challenge |
| `POST /api/auth/password-reset/confirm` | New. `{challengeId, code, newPassword}` → 204 |
| `GET /api/users/me` | `phone` now returned for every role; new `phoneVerified` |

New error codes: `DUPLICATE_PHONE` (409), `PHONE_VERIFICATION_REQUIRED` (403),
`PHONE_ALREADY_VERIFIED` (409), `OTP_ATTEMPTS_EXCEEDED` (429), `OTP_DELIVERY_FAILED` (502).

## 8. Provider configuration

No secret is committed. Credentials come from the AWS default provider chain (env → profile →
container → IMDS/IAM role) in both transports.

```
EMAIL_MODE=ses            # log | ses     (log refused outside local/test/demo)
EMAIL_FROM=               # verified SES identity — startup fails if empty when mode=ses
EMAIL_SES_REGION=eu-central-1
EMAIL_TIMEOUT_MS=10000

SMS_MODE=aws              # log | aws     (log refused outside local/test/demo)
AWS_SMS_REGION=eu-central-1
AWS_SMS_SENDER_ID=        # optional; blank = account default origination identity
SMS_TIMEOUT_MS=10000

PHONE_DEFAULT_REGION=IL
TRUSTED_PROXIES=          # e.g. 10.0.0.0/16 — REQUIRED behind an ALB, see below
```

### Required AWS-side setup (not doable from code)

**SES:** `EMAIL_FROM` must be a verified identity (address, or an address on a DKIM-verified
domain); the account must be **out of the SES sandbox** for the target region, otherwise SES delivers
only to verified recipients — which looks exactly like "email works" in testing and exactly like "no
customer ever receives a code" in Production. Execution role needs `ses:SendEmail`.

**SMS to Israel (`+972`) — investigated, not yet validated.** Findings to confirm against the AWS
console during MS5:

- Israel does **not** support unregistered alphanumeric sender IDs for A2P traffic; alphanumeric
  sender IDs generally require pre-registration through AWS Support/End User Messaging, and
  unregistered alphanumeric traffic is liable to be filtered or rejected by Israeli carriers.
  `AWS_SMS_SENDER_ID` is therefore optional by design — blank lets AWS select an origination
  identity from the account pool.
- An **origination identity** (sender ID, long code, or 10DLC/short code as applicable) must exist in
  the account for the chosen region before any `+972` message can be delivered.
- New accounts are in the **SMS sandbox**: delivery is limited to explicitly verified destination
  phone numbers, and a **Production access** request plus a **spend-limit increase** is required
  before real customers can receive codes.
- Not every AWS region supports SMS to every country; the configured `AWS_SMS_REGION` must be one
  that originates to Israel.

**None of this has been exercised against a real AWS account.** It is documented research, and it is
recorded here as research — see §11.

**`TRUSTED_PROXIES` is not optional behind an ALB.** Left empty, every request appears to come from
the load balancer and all users share one rate-limit counter: the register limiter (10 per 10
minutes) would become a platform-wide cap of 10. That is a self-inflicted outage, not a weaker
limiter.

## 9. Security review

**Closed by this milestone**

| Finding | Resolution | Evidence |
|---|---|---|
| Password alone issued a JWT | Two-step everywhere; only 2 methods mint a session | `AuthFlowTest` (33 tests, asserts `session == null` at every pre-OTP point) |
| OTP written to logs at `INFO` | Code logged only when `environment=local` | `OtpLoggingTest` — captures appender output and asserts no 6-digit sequence survives |
| Plaintext OTP at rest | SHA-256 hex; `code` column dropped | `MigrationIntegrationTest.v47_dropsPlaintextAndAddsTheOtpHardeningColumns` |
| No attempt cap per code | 5, enforced by conditional UPDATE on its own transaction | `OtpServiceTest.redeem_fiveWrongGuesses_thenTheChallengeIsDeadEvenForTheCorrectCode` |
| No resend, no cooldown | Resend endpoint; 60 s cooldown; 5/hour/purpose; replaces predecessor | `OtpServiceTest`, `AuthFlowTest` |
| Phone not unique / not an identity | `ux_users_phone` + `ck_users_phone_e164`, E.164 via libphonenumber | `MigrationIntegrationTest` |
| Duplicate-email race → 500 | `DataIntegrityViolationException` handler → 409 | `GlobalExceptionHandlerTest` |
| `/verify` enumeration leak | All OTP flows addressed by opaque `challengeId`; unknown challenge and wrong code both answer `INVALID_CODE` | `OtpServiceTest`, `AuthFlowTest` |
| No recovery path | Reset request/confirm, enumeration-neutral, invalidates outstanding challenges | `AuthFlowTest` (7 recovery tests) |
| `getRemoteAddr()` rate limiting | Trusted-proxy-aware resolution | `ClientIpResolverTest` (15), `AuthRateLimitInterceptorTest` (8) |
| Unbounded limiter memory | Sweep + hard ceiling | `AuthRateLimitInterceptorTest` |
| Email lookups missed the index | Canonical storage + `ux_users_email` + `findByEmail` | `MigrationIntegrationTest` |

**Verified unchanged**

- BCrypt, cost 10, unchanged; the recovery path re-encodes through the same encoder.
- `role = ADMIN` still refused at registration, before any row is written, including with an
  otherwise-valid payload (`AuthServiceTest`, 2 tests).
- Professional approval lifecycle, verification-document handling, SOS lifecycle, service coverage
  and polling behaviour untouched — the whole pre-existing suite passes unmodified except for
  constructor arity.
- Soft-delete semantics deliberate and now consistent: `deleteMe` releases both identifiers.
- No privilege regression: `/api/auth/phone/capture` is matched `.authenticated()` **before** the
  `permitAll` on `/api/auth/**`, since Spring Security's first match wins.

**Residual, by design**

- Password reset cannot report a genuine delivery failure without becoming an existence oracle. A
  user whose mail provider rejects our messages sees no email and no error.
- JWT stays in `localStorage`, 24 h, no refresh, no rotation on password reset (existing tokens
  survive a reset; only outstanding *challenges* are invalidated). Explicitly an MS4 item.
- The rate limiter is single-instance only. Not multi-instance safe. MS4/MS5.

## 10. Automated validation

Run from the final tree.

```
backend:  mvn -B clean verify   -> BUILD SUCCESS, Tests run: 897, Failures: 0, Errors: 0, Skipped: 1
frontend: npx tsc -b            -> clean
          npm run lint          -> clean (3 pre-existing react-refresh warnings)
          npm run build         -> built in 780ms
```

The single skip is the pre-existing `OpenAiClassificationEvaluationRunnerTest`, which requires an
OpenAI key and was already skipped at baseline.

New coverage, 158 tests: identity and normalization (23), OTP lifecycle (20), end-to-end auth flows
(33), recovery (7 of those), client-IP/proxy (15), rate limiting (8), provider startup guards (17),
OTP-not-logged (11), contact gate (7), **real-PostgreSQL migration and constraint tests (19)**,
duplicate-race handler (4).

`MigrationIntegrationTest` stages the legacy cohort honestly: Flyway is run to `target = 45` (the
exact pre-MS1 schema), dirty rows are inserted the way the old code would have written them, and only
then is the rest of the path applied. It skips itself when no PostgreSQL server is reachable.

## 11. Real-provider validation

### Performed — local end-to-end, logging transports, real database

Executed against the built jar and the real development PostgreSQL:

| Scenario | Result |
|---|---|
| Migration on a real 95-user database | 3 migrations applied, 4 phones canonicalized, 0 verified-phone grandfathering |
| Register customer (`MS1.Smoke@Example.COM`, `050-223-4567`) | `nextStep=VERIFY_EMAIL`, `session: null`, destination masked `m***@example.com` |
| Verify email | `nextStep=VERIFY_PHONE`, SMS challenge issued, `session: null` |
| Verify phone | `nextStep=AUTHENTICATED`, JWT issued, `emailVerified/phoneVerified` both true |
| Email login → OTP | userId **106** |
| Phone login (`0502234567`, local spelling) → OTP | userId **106** — same account |
| Email canonicalization | Stored as `ms1.smoke@example.com` |
| Phone canonicalization | Stored as `+972502234567` |
| Unverified phone → `POST /api/issues` | `403 PHONE_VERIFICATION_REQUIRED` |
| Unverified phone → email login | Still works (challenge issued) |
| Unverified phone → phone login | `INVALID_CREDENTIALS` |
| Password reset, known vs unknown account | Byte-identical response shapes; both `delivered: true` |
| Duplicate email / duplicate phone | `DUPLICATE_EMAIL` / `DUPLICATE_PHONE` |
| Israeli landline at registration | `VALIDATION_ERROR`, `phone: must be a mobile number that can receive SMS` |
| Token in any pre-OTP response body | Zero occurrences |

Test data was removed from the development database afterwards.

### NOT performed — the two Gate items requiring AWS

- **Real email delivered to a real inbox via SES.** Not done. No AWS credentials, no verified SES
  identity, no sandbox exit.
- **Real SMS delivered to a real Israeli mobile number via AWS End User Messaging.** Not done. No AWS
  account access, no origination identity, no SMS Production access, no spend limit.
- Consequently the Israel-specific delivery findings in §8 are **documented research only** and have
  not been confirmed against a live account, exactly as the milestone instructions require me to
  state rather than to fake.

Also not performed: browser-driven E2E through the actual UI. The frontend typechecks, lints and
builds, and the API contract it targets is the one validated above, but no click-through was
executed.

## 12. Known limitations

1. Real Email/SMS delivery unvalidated (§11) — the blocking one.
2. No browser E2E evidence.
3. Rate limiter is in-memory and single-instance; resets on deploy.
4. A page refresh during an OTP step loses the challenge (router state, deliberately not persisted);
   recovery costs one password entry.
5. Password reset gives no feedback on delivery failure (enumeration-neutrality trade-off).
6. JWTs issued before a password reset remain valid until they expire.
7. Every professional in the database is currently **ineligible**, because `phone_verified` defaults
   to `false` and eligibility now requires it. Intended for a pre-launch platform; the path back is
   the phone-capture flow. The demo dataset seeds itself verified.
8. `AWS_SMS_SENDER_ID` blank by default — correct for Israel pending registration, but it means the
   sender shown to users is whatever AWS selects.

## 13. Production risks remaining

| Risk | Severity | Owner milestone |
|---|---|---|
| SES sandbox / SMS sandbox not exited → no customer receives a code | **Critical** | MS5 |
| No Israeli origination identity → SMS silently filtered | **Critical** | MS5 |
| `TRUSTED_PROXIES` unset behind an ALB → platform-wide rate-limit lockout | **High** | MS5 |
| Single-instance rate limiter | Medium | MS4/MS5 |
| JWT in `localStorage`, no refresh/rotation | Medium | MS4 |
| SMS cost abuse within the per-user caps (5/hour × many accounts) | Medium | MS4 |
| No alerting on `OTP_DELIVERY_FAILED` rate | Medium | MS5 |

## 14. Definition-of-Done checklist

| Gate requirement | Status |
|---|---|
| Real Email delivery works | ❌ **Not validated** |
| Real SMS delivery works | ❌ **Not validated** |
| Email login works end to end | ✅ locally |
| Phone login works end to end | ✅ locally |
| Both resolve to the same user | ✅ userId 106 both paths |
| Verification/recovery path exists | ✅ |
| OTP is not logged | ✅ asserted mechanically |
| Security tests pass | ✅ |
| Backend + frontend builds pass | ✅ |
| No JWT before the second factor | ✅ |
| Duplicate email/phone, normalization, attempt cap, resend rules | ✅ |
| Provider-failure paths | ✅ automated; ⚠️ against mocks, not a live provider |
| Rate limiting correct behind AWS proxy | ✅ logic tested; ⚠️ not exercised behind a real ALB |

## 15. Final gate decision

**`PARTIAL`.**

Everything implementable without AWS access is implemented, tested and validated end to end against
a real database. Two Gate requirements — real email delivery and real SMS delivery to an Israeli
handset — cannot be satisfied from this environment and have not been simulated, approximated, or
declared complete.

To reach `DONE`, and nothing else is outstanding:

1. Provision AWS credentials with `ses:SendEmail` and `sns:Publish`.
2. Verify an SES sender identity; request SES Production access for the region.
3. Create an Israeli origination identity; request SMS Production access and a spend limit; confirm
   the sender-ID/registration findings in §8 against the live console.
4. Deploy with `EMAIL_MODE=ses`, `SMS_MODE=aws`, `TRUSTED_PROXIES` set.
5. Re-run the §11 scenarios against real providers and record the evidence here.

Until then MS1 must not be marked `DONE`, and MS2 must not begin.

---

# Part 2 — Pre-DONE audit and remediation (2026-08-25)

A full pre-DONE security and architecture audit was run against the working tree before requesting
live provider validation. It found three HIGH issues and six MEDIUM ones. All eleven are now fixed.
**MS1 status is still `PARTIAL`** — the two Gate items that need AWS remain outstanding.

## A. What the audit found

The audit confirmed the architecture: JWT issuance really is confined to two post-OTP paths
(`generateToken` has exactly one caller, `AuthService.session`, reached only from `verifyPhone` and
`loginOtp`), email and phone really do resolve to the same row, professional eligibility really is
centralized in `ELIGIBLE_JPQL` across all six discovery paths, and V46/V47/V48 really are atomic.

It also found the following.

| # | Severity | Finding |
|---|---|---|
| 1 | HIGH | **`demo` and `test` could not authenticate at all.** The logging transports revealed the OTP only when `isLocal()`, but `ProviderModeStartupGuard` permits logging transports in `local`/`test`/`demo`. Those two environments started successfully and withheld every code, so nobody could complete a login or a registration in the environments MS1 is meant to be validated in. `OtpLoggingTest` *asserted* the broken behaviour |
| 2 | HIGH | **Unsalted, unkeyed `SHA-256` of a 6-digit code.** 10⁶ possible values: a ~32 MB precomputed table reverses every stored challenge by lookup, and each row also carries the `challenge_id` needed to redeem it. A read-only database disclosure was account takeover for everyone mid-login. A per-row salt would **not** have fixed this — it only defeats precomputation, and 10⁶ candidates are brute-forceable per row in milliseconds |
| 3 | HIGH | **The database transaction was held across the provider call.** `OtpService.issue` was `@Transactional` and called SES/SNS inside it, with a 10 s timeout and HikariCP's default pool of 10. Ten concurrent registrations against a slow provider exhaust the pool and stall the whole application |
| 4 | HIGH | **`X-Forwarded-For` was read with `getHeader()`**, which returns only the first header line. A client sending its own XFF as a separate line could have that line read instead of the balancer's |
| 5 | MEDIUM | **Login timing oracle.** An unknown identifier threw before any BCrypt work; a known one paid ~100 ms |
| 6 | MEDIUM | **Password-reset timing oracle**, larger than login's — a real account did a hash, an insert and a provider round trip while the decoy returned instantly |
| 7 | MEDIUM | **Unlimited phone enumeration via `PUT /api/users/me`**, which returns `DUPLICATE_PHONE` and had no rate limiter |
| 8 | MEDIUM | **`POST /api/issues/classify` was not phone-gated**, leaving an unverified account able to spend OpenAI requests indefinitely |
| 9 | MEDIUM | **AWS SDK log level unpinned.** The SDK logs request bodies at DEBUG; for SNS that body is the SMS text containing the OTP |
| 10 | MEDIUM | **No real concurrency tests.** `InMemoryVerificationCodes` is single-threaded and models no row locks, so single-use, the attempt cap and resend invalidation were reasoned about but never proven |
| 11 | LOW | `consumeIfValid` had no expiry predicate — a TOCTOU window between the Java check and the UPDATE |

## B. What was changed

### 1. OTP visibility policy (HIGH)

`LoggingEmailSender`/`LoggingSmsSender` now reveal the code when `!environment.isProductionLike()`,
which is *exactly* the set of environments `ProviderModeStartupGuard` permits a logging transport in.
The two rules are now the same rule, and `OtpLoggingTest.theLoggingFenceAndTheStartupGuardAgreeExactly`
asserts they cannot drift apart.

```
local / test / demo  -> logging transport allowed, OTP readable
staging / production -> real providers required, logging transport refused at startup
```

### 2. Keyed HMAC with a server-side pepper (HIGH)

New `auth.service.OtpPepper`:

```
HMAC-SHA256(pepper, challengeId + ":" + purpose + ":" + code)
```

- `pronto.otp.pepper` / `OTP_PEPPER`, distinct from `JWT_SECRET`, never stored in the database,
  never logged, never returned.
- The challenge id and purpose are bound into the message so the same six digits on two challenges
  hash differently — no cross-challenge equivalence, and a hash recovered once is not recognizable
  elsewhere.
- Output is 64 hex characters, the same width `V47`'s `code_hash` column already has, so **no new
  migration was required**.
- Existing outstanding local codes are not backward compatible, which is correct pre-production:
  they simply stop verifying and the user resends.
- `ProductionHardeningStartupGuard` refuses to start a production-like environment with the
  placeholder, an empty value, or anything under 32 characters. `test` injects a deterministic
  pepper (`OtpServiceTest.TEST_PEPPER`).

### 3. Provider calls moved out of transactions (HIGH)

`OtpService.issue` is no longer `@Transactional`. Two new collaborators hold the database work,
following the existing `LoginAttemptRecorder`/`OtpAttemptRecorder` pattern:

- **`OtpChallengeWriter`** — `create` / `supersedePrevious` / `abandon`.
- **`AuthAccountWriter`** — `createAccount`, `redeemEmailVerification`, `attachPhone`,
  `verifyPassword`, `resolveIdentifier`, `loadActive`.

`AuthService`'s six dispatching methods became non-transactional orchestrators. The three that never
dispatch (`verifyPhone`, `loginOtp`, `confirmPasswordReset`) keep their transactions.

**The ordering is the part that matters:**

```
create()            -> commit   (previous code still valid)
dispatch()                      (no connection held)
supersedePrevious() -> commit   on success: the new code is the only one
abandon()           -> commit   on failure: the new code dies, previous survives
```

The obvious split — invalidate-and-insert, then dispatch — is the bug: a failed dispatch would
destroy a code the user could still have typed and replace it with one that never arrived. Here a
failed resend is a **no-op**, proved by `OtpServiceTest.resendFailure_leavesThePreviousCodeUsable`.

Plain `@Transactional` is used rather than `REQUIRES_NEW`, deliberately: on the registration path
`REQUIRES_NEW` would deadlock, because the `verification_codes.user_id` foreign key would have to see
a `users` row that the suspended outer transaction had not committed.

**Registration failure semantics changed as a result**, and for the better: a delivery failure no
longer rolls back the account (which previously also orphaned an uploaded verification document and
made a professional re-fill the whole form). The account persists, the caller gets
`OTP_DELIVERY_FAILED`, and the recovery path is to log in — which returns a fresh `VERIFY_EMAIL`
challenge for an account that never finished verifying.

**Hikari pool size was deliberately left at its default.** Enlarging the pool would treat the symptom;
the network call is simply no longer inside the transaction. Provider timeouts were reviewed and are
finite and explicit: `apiCallTimeout` and `apiCallAttemptTimeout` are both set to
`pronto.email.timeout-ms` / `pronto.sms.timeout-ms`, default 10 000 ms, which bounds the whole attempt
including SDK retries. There is no application-level retry on top, so an SDK retry cannot become a
duplicate user-visible send.

### 4. Multi-instance `X-Forwarded-For` (HIGH)

`ClientIpResolver` now reads `request.getHeaders("X-Forwarded-For")`, joins every line in order into
one logical chain, and then applies the unchanged trusted-proxy algorithm. Six new tests cover
multi-line chains, forged leading lines, blank lines and malformed entries.

### 5 & 6. Timing symmetry

`AuthAccountWriter` holds one BCrypt hash of a random value, computed once per application start
(not per request — that would be a free CPU-exhaustion primitive). The unknown-identifier branch of
`verifyPassword` verifies against it, and `requestPasswordReset`'s decoy branch calls
`burnEquivalentPasswordWork()`.

`AuthTimingTest` asserts invocation counts rather than wall-clock, because a timing assertion on
shared CI is a flaky test and a flaky security test gets deleted.

**Residual, stated honestly:** this equalises the order of magnitude, not the exact duration. A slow
or timing-out provider still makes the real password-reset branch measurably longer. Fully closing
that gap requires decoupling dispatch from the response — a queue — and MS1 deliberately introduces
no queue. Recorded for MS4.

### 7–11. The remainder

- **`PUT /api/users/me`** now carries the same `AuthRateLimitInterceptor`, 20 per 15 minutes. Applied
  to the whole endpoint rather than only phone-changing requests: the limiter runs before body
  binding, and a threshold that never bites a legitimate profile edit costs nothing to apply
  uniformly.
- **`IssuesService.classify`** calls `ContactVerificationGuard`. The route is authenticated and
  `CUSTOMER`-only (`SecurityConfig` + `IssuesWebConfig`), so **there is no anonymous classification
  flow this breaks** — checked before changing it.
- **`logging.level.software.amazon.awssdk: WARN`** pinned in `application.yml`, and both senders now
  log `AwsErrorSummary.of(e)` (exception type + AWS error code + request id + status) instead of
  `e.toString()`, which for SNS routinely echoes the destination phone number back.
- **`consumeIfValid`** carries `expires_at > :now`. The unconditional `consume` is kept for
  abandoning an undelivered challenge, which must work regardless of age.
- **`ProductionHardeningStartupGuard`** fails startup in production-like environments when
  `TRUSTED_PROXIES` is empty while `pronto.security.behind-proxy` is true. **Fail-fast rather than a
  warning**, because a warning is a log line nobody reads and the symptom ("some users can't
  register") points nowhere near the cause. A deployment genuinely reached directly sets
  `behind-proxy=false` and thereby writes that decision down.

## C. Concurrency tests — the four cases, on real PostgreSQL

`migration.OtpConcurrencyIntegrationTest` builds the genuine `VerificationCodeRepository` (real JPQL,
real Hibernate, real JDBC) over a scratch database and releases eight threads from a
`CountDownLatch`. It uses a **shared, transaction-aware** `EntityManager` proxy, so each thread runs
in its own real transaction exactly as a request would.

| Case | Result |
|---|---|
| **A** — 8 threads redeem the same valid code | Exactly **1** UPDATE matched; 7 returned 0. One row consumed |
| **B** — 8 threads submit wrong codes against a cap of 5 | Exactly **5** increments accepted; `attempts = 5`, never 6 |
| **C** — resend races redemption | The old challenge is closed exactly once, by exactly one of the two; a later redemption always returns 0; the replacement is untouched. **No state exists where a superseded code still redeems** |
| **D** — expired code | Refused by the statement, not only the service check; 8 concurrent attempts all return 0 |

Plus: abandoning an undelivered challenge works even after expiry.

## D. Validation

```
backend:  mvn -B clean verify   -> BUILD SUCCESS, Tests run: 943, Failures: 0, Errors: 0, Skipped: 1
frontend: npx tsc -b            -> clean
          npm run lint          -> 0 errors
          npm run build         -> built in 875ms
```

943 tests, up from 897 — 46 added by this remediation. The single skip is the pre-existing
`OpenAiClassificationEvaluationRunnerTest`, which needs an OpenAI key and was already skipped at
baseline.

Local end-to-end was re-run against the built jar and the real development database, with logging
transports:

| Flow | Result |
|---|---|
| register → verify-email → verify-phone | `AUTHENTICATED`, token issued, userId **107** |
| email + password → email OTP → JWT | userId **107** |
| phone + password (`0502234567`) → SMS OTP → JWT | userId **107** — same account |
| `session` on every pre-OTP response | `null` |
| stored `code_hash` | 64 hex chars, keyed, no plaintext anywhere |
| OTP-shaped values in the log outside the `[DEV …]` lines | **0** |

Test data was removed afterwards.

## E. Residual risks after remediation

1. **Password-reset timing is equalised in order of magnitude only** (§B.5-6). MS4.
2. **JWTs issued before a password reset remain valid** until expiry. Only outstanding *challenges*
   are invalidated. Revoking tokens needs a version column or a denylist — session-management work
   that belongs with MS4's refresh/rotation decisions, not with contact verification. **Confirmed
   still true; deliberately deferred.**
3. **A brief two-live-codes window** between `create` and `supersedePrevious`, lasting one provider
   call. During it the older code is the one the user actually holds. "Resend invalidates the
   previous code" holds once the operation completes.
4. **The rate limiter remains in-memory and single-instance.** MS4/MS5.
5. **A provider that sends then errors** (e.g. post-accept timeout) leaves the user holding a code
   the database has abandoned. Confusing, not dangerous — resend recovers.
6. **A failed migration requires `flyway repair`** before retry. PostgreSQL's transactional DDL means
   no partial schema or data change survives, but `flyway_schema_history` keeps the failed row.
   Documentation only; no migration logic was changed for it.

## F. Live-provider validation — still outstanding

Unchanged from Part 1 §11. Neither has been performed and neither is claimed:

- **Real email delivered to a real inbox via Amazon SES.**
- **Real SMS delivered to a real Israeli mobile via AWS End User Messaging.**

The Israel-specific requirements in §8 remain documented research, unconfirmed against a live
console. Required AWS-side configuration is unchanged, plus two new variables:

```
OTP_PEPPER=<32+ char secret, distinct from JWT_SECRET>
TRUSTED_PROXIES=<ALB subnet CIDRs, e.g. 10.0.0.0/16>   # or BEHIND_PROXY=false
```

`TRUSTED_PROXIES` must be the load balancer's **subnet CIDRs** — never its DNS name, and never AWS's
published public ranges.

## G. Status

**MS1 remains `PARTIAL`.** Every implementable item is implemented, tested and validated locally; the
two AWS-dependent Gate items are not done and are not simulated. MS2 has not been started.

> **Superseded by Part 3.** Both AWS-dependent Gate items were subsequently performed live and
> passed. Part 2 §F ("still outstanding") is historical from this point on.

---

# Part 3 — Live-provider validation and OTP copy cleanup (2026-08-25)

Part 1 and Part 2 both closed on the same blocker: real SES and real SMS had never been exercised
against an AWS account. That has now happened. This part records what passed, the one thing that is
still open and is **not** a code item, and the presentation-only copy change made afterwards.

## H. Live-provider validation — PERFORMED, PASS

| Gate item | Result |
|---|---|
| Real email delivered to a real inbox via Amazon SES | ✅ **PASS** |
| Real SMS delivered to a real Israeli `+972` mobile via AWS End User Messaging (`SNS::Publish`) | ✅ **PASS** |
| Sender ID `PRONTO` on the Israeli destination | ✅ **validated** — accepted and displayed |
| Email OTP verification, end to end through Pronto | ✅ **PASS** |
| Phone OTP verification, end to end through Pronto | ✅ **PASS** |

This retires Part 1 §11's "NOT performed" list and Part 2 §F in full. The Israel-specific findings in
§8 are no longer "documented research": a `+972` handset received a Pronto OTP and the OTP was
redeemed through the real flow.

Operational configuration that produced the passing send:

```
EMAIL_MODE=ses
SMS_MODE=aws
AWS_SMS_SENDER_ID=PRONTO      # §8 recorded this as optional-and-blank; it is now set and validated
```

Known limitation #8 in §12 ("`AWS_SMS_SENDER_ID` blank by default … the sender shown to users is
whatever AWS selects") is therefore resolved for the configured deployment: the sender shown is
`PRONTO`. The default in `application.yml` stays blank, which is still correct — it is the safe value
for any account that has not registered a sender ID.

## I. Still required before public Production — the SMS sandbox

**This is the distinction the rest of this section exists to keep straight:**

```
Live SMS technical validation           = PASS
SMS Production Access / sandbox exit    = still required before real public Production users
```

The AWS account **remains in the SMS sandbox.** In the sandbox, `SNS::Publish` succeeds only for
destination numbers that have been explicitly verified in the End User Messaging console. A send to
an unverified number does not raise an error the application can see — it is simply not delivered.

So the following is **not** claimed and must not be inferred from §H:

- ❌ that an arbitrary, unverified customer `+972` number receives a Pronto OTP today.

What §H proves is that the transport, the credentials, the region, the origination identity, the
sender ID and both application flows are correct — every failure mode that a sandbox exit would
*not* fix has been eliminated. What remains is an AWS account-state request:

1. **Request SMS Production access** (sandbox exit) for the configured region.
2. **Request a spend-limit increase** sized to expected OTP volume; the sandbox default will throttle
   real traffic immediately.
3. Re-verify after the exit that a **previously unverified** `+972` number receives a code — that is
   the assertion the sandbox makes impossible today.

Until step 3 is recorded here, §13's "SMS sandbox not exited → no customer receives a code" risk
stays **Critical** and stays open. The SES side of that row is closed by §H.

## J. OTP copy cleanup — Hebrew RTL email and shorter SMS

Presentation only. No change to OTP generation, hashing, pepper, TTLs, attempt caps, resend
cooldowns, transaction boundaries, provider selection, or logging policy.

### The email rendered left-to-right

The SES message was Hebrew text in a `text/plain` body only. Mail clients apply their own
`direction: ltr` container and most do not run first-strong-character detection, so the copy rendered
left-aligned — correct characters, wrong reading order for the audience.

`OtpMessageCopy.emailHtmlBody(purpose, code)` now produces an HTML alternative part, and
`SesEmailSender` sends **both** parts (`multipart/alternative`); the plain-text body is retained, not
replaced. The HTML states direction explicitly rather than relying on client detection:
`lang="he"` and `dir="rtl"` on `<html>`, `dir="rtl"` on `<body>`, and an inline
`direction:rtl; text-align:right` pair on the layout cell and on all five text paragraphs — seven
declarations, asserted by test, because a single declaration on an ancestor is exactly what a mail
client's own stylesheet overrides. Gmail discards `<head>`/`<style>` outright, so there is no
stylesheet and nothing is fetched from a URL; markup is nested tables with inline styles, which is
the subset Outlook, Gmail mobile and iOS Mail render identically. The six digits sit in their own
`dir="ltr"` centred block so the bidirectional algorithm cannot reorder them against adjacent
punctuation.

The copy stays centralized in `auth.email.OtpMessageCopy` — the HTML template lives there beside the
text and SMS copy, so the SES sender still contains no customer-facing wording.

### Copy, per purpose

Subjects remain **three distinct lines** across the three email purposes. That is deliberate and was
not collapsed into one: a login code arriving in an inbox its owner did not ask for is proof that
somebody else has their password, and it is only actionable if the subject says so without the
message being opened (`EmailSender` javadoc; asserted by
`OtpMessageCopyTest.email_theThreeEmailPurposesKeepThreeDistinctSubjects`).

| Purpose | Subject | TTL in copy |
|---|---|---|
| `EMAIL_VERIFICATION` | `קוד האימות שלך ב-Pronto` | 15 min |
| `EMAIL_LOGIN_OTP` | `קוד ההתחברות שלך ל-Pronto` | 10 min |
| `PASSWORD_RESET` | `קוד לאיפוס הסיסמה שלך ב-Pronto` | 15 min |

Password reset now has its own wording throughout; it previously shared the verification phrasing in
the SMS template, which would have described a password reset as an "אימות".

**Every TTL sentence is formatted from `OtpPurpose.timeToLive()`, never written as a literal.** A
hardcoded "15 דקות" reads correctly for verification and lies to every login recipient, because the
two windows genuinely differ. `OtpMessageCopyTest.email_theStatedValidityIsAlwaysTheConfiguredOne`
asserts this for all five purposes.

One deliberate consequence of adopting the specified email body: the previous anti-social-engineering
sentence ("אל תעבירו את הקוד לאף אחד — נציגי Pronto לעולם לא יבקשו אותו") is gone from the email. The
"if you didn't request this, ignore it" line — the security signal the copy exists to carry — is
retained. Flagged as a copy trade-off, not a control regression.

### SMS shortened to one segment

Hebrew has no GSM-7 representation, so every Pronto OTP SMS is UCS-2: **70 characters in a single
segment, 67 per segment once concatenated.** The previous copy carried the email's disclaimer
sentence and crossed that boundary on every purpose — meaning every OTP the platform has ever sent by
SMS was billed as two segments and carried a handset-side reassembly step that can fail.

| Purpose | Before | After | Chars | Segments |
|---|---|---|---|---|
| `PHONE_VERIFICATION` | 73 chars, 2 segments | `Pronto: קוד האימות שלך הוא 483920. הקוד תקף ל-15 דקות.` | **54** | **1** |
| `PHONE_LOGIN_OTP` | 75 chars, 2 segments | `Pronto: קוד ההתחברות שלך הוא 483920. הקוד תקף ל-10 דקות.` | **56** | **1** |
| `PASSWORD_RESET` | 73 chars, 2 segments | `Pronto: קוד לאיפוס הסיסמה שלך הוא 483920. הקוד תקף ל-15 דקות.` | **61** | **1** |

Every purpose now fits one UCS-2 segment, halving SMS cost per OTP. `OtpMessageCopyTest` asserts the
70-character bound for every purpose — and first asserts that every character is in the BMP, so
`String.length()` is genuinely the code-unit count a carrier counts rather than a Java-side proxy for
it. No encoding is set manually anywhere; SNS selects UCS-2 from the payload as before.

Sender ID remains `PRONTO`. No marketing text, no disclaimer paragraph, OTP appears exactly once.

### Security posture — unchanged, re-asserted

| Property | Status |
|---|---|
| OTP never in a production-like application log | ✅ `OtpLoggingTest`, 14 tests, unchanged and passing |
| `software.amazon.awssdk: WARN` pin (SDK DEBUG logs the SMS body, i.e. the OTP) | ✅ untouched in `application.yml` |
| HMAC / pepper / attempt cap / resend cooldown | ✅ untouched |
| Secrets in templates or config | ✅ none added; the templates take only `purpose` and `code` |
| Purpose-specific TTLs (15 / 10) | ✅ preserved, and now asserted from the enum |

## K. Files changed in Part 3

| File | Change |
|---|---|
| `backend/src/main/java/com/pronto/auth/email/OtpMessageCopy.java` | New `emailHtmlBody`; subjects, email body and SMS body rewritten; TTLs formatted from the enum |
| `backend/src/main/java/com/pronto/auth/email/SesEmailSender.java` | Sends `text` + `html` alternative parts for OTPs; order-status email stays text-only |
| `backend/src/test/java/com/pronto/auth/email/OtpMessageCopyTest.java` | New — 47 tests over RTL markup, copy, TTL derivation and SMS segment bounds |
| `docs/production-roadmap/reports/prod-MS1-report.md` | This part; status banner and test count updated |

No provider architecture was redesigned; `SmsSender`/`EmailSender`, transport selection and
`ProviderModeStartupGuard` are untouched.

## L. Validation

```
backend: mvn -o -B clean verify -> BUILD SUCCESS
                                   Tests run: 990, Failures: 0, Errors: 0, Skipped: 1
```

990 tests, up from 943 — 47 added here. The single skip is the pre-existing
`OpenAiClassificationEvaluationRunnerTest`, which needs an OpenAI key and was already skipped at
baseline. No further real SES or SMS send was performed for this copy change.

## M. Status

**MS1 is `PARTIAL`, pending final Gate review** — not `DONE`, and that decision has not been taken
here.

Both AWS-dependent Gate items now pass live (§H). The remaining item is operational, not
implementable: **SMS Production access / sandbox exit, plus a spend limit** (§I). Until that is
granted and re-verified against a previously unverified `+972` number, the platform can send OTPs
only to numbers verified in the AWS console.

**MS2 has not been started.** Nothing in this part was committed, pushed or merged.

> **Superseded by Part 4.** The Gate review has since been performed and MS1 is `DONE`.

---

# Part 4 — Final Gate review (2026-08-25)

The review that Part 3 §M deferred. Nothing was implemented in this part: it is an audit of the
working tree, a validation run, and a decision.

## N. Gate verdict

```text
MS1 — DONE
```

Every MS1 Gate requirement is now proven, including the two that held the milestone at `PARTIAL`
through Parts 1-3.

| Gate requirement | Status | Evidence |
|---|---|---|
| Real Email delivery works | ✅ | Part 3 §H — live SES to a real inbox |
| Real SMS delivery works | ✅ | Part 3 §H — live `SNS::Publish` to a `+972` handset, sender ID `PRONTO` |
| Email login works end to end | ✅ | live + `AuthFlowTest` |
| Phone login works end to end | ✅ | live + `AuthFlowTest` |
| Both resolve to the same user | ✅ | §11, §D — one `users.id` from either identifier |
| Verification/recovery path exists | ✅ | register→email→phone→JWT; password reset implemented |
| OTP is not logged | ✅ | `OtpLoggingTest`, 14 tests, asserted mechanically |
| Security tests pass | ✅ | §P |
| Backend + frontend builds pass | ✅ | §P |
| No JWT before the second factor | ✅ | `session()` has two callers, both post-`redeem` |
| Duplicate email/phone, normalization, attempt cap, resend rules | ✅ | §P |
| Provider-failure paths | ✅ | automated; and the live path is now exercised |
| Rate limiting correct behind AWS proxy | ✅ logic | ⚠️ still not exercised behind a real ALB — MS5 |

This supersedes Part 1 §14 and §15, and Part 3 §M.

**`DONE` is the MS1 implementation gate and nothing wider.** Pronto is **not** Production ready:
MS2-MS7 are outstanding, and the prerequisites in §O are real and unmet.

## O. Production operational prerequisites before public launch

Carried forward verbatim, unresolved, and **explicitly not MS1 code defects** — no MS1 change can
satisfy any of them:

```text
Production operational prerequisites before public launch:
- Exit AWS SMS sandbox (request SMS Production Access)
- Increase/review SMS spend limit
- Validate a previously unverified +972 destination after sandbox exit
- Configure TRUSTED_PROXIES with actual ALB subnet CIDRs
```

The account is **still in the SMS sandbox**. Live SMS technical validation is `PASS` (Part 3 §H);
delivery to an *arbitrary unverified* customer number is **not** claimed and does not work today.
`TRUSTED_PROXIES` cannot be forgotten silently — `ProductionHardeningStartupGuard` refuses to start a
production-like environment without it, or without a real `OTP_PEPPER`. Owner: MS5.

## P. Final validation

```
backend:  mvn -o -B clean verify  -> BUILD SUCCESS
                                     Tests run: 990, Failures: 0, Errors: 0, Skipped: 1
frontend: npx tsc -b               -> clean (exit 0)
          npm run lint             -> 0 errors, 3 pre-existing warnings
          npm run build            -> built in 996ms
```

The single skip is `OpenAiClassificationEvaluationRunnerTest`, which needs an OpenAI key and was
already skipped at baseline. The three lint warnings are `react(only-export-components)` in
`ProfessionalList.tsx` and `ProfessionIllustration.tsx` — neither file is in the MS1 diff.

Every gate-relevant suite ran and passed in that build:

| Suite | Tests |
|---|---|
| `AuthFlowTest` | 33 |
| `AuthServiceTest` | 45 |
| `AuthTimingTest` | 7 |
| `OtpServiceTest` | 28 |
| `OtpLoggingTest` | 14 |
| `OtpMessageCopyTest` | 47 |
| `OtpConcurrencyIntegrationTest` (real PostgreSQL) | 7 |
| `MigrationIntegrationTest` (real PostgreSQL) | 19 |
| `ClientIpResolverTest` | 21 |
| `ProviderModeStartupGuardTest` | 17 |
| `ProductionHardeningStartupGuardTest` | 14 |
| `ContactVerificationGuardTest` | 7 |
| `IssuesServiceTest` (classify gate) | 20 |
| `AuthRateLimitInterceptorTest` | 8 |
| `PhoneNumberNormalizerTest` | 23 |

The two PostgreSQL suites took 40.6 s and 5.0 s respectively — they connected to a real server and
ran, rather than self-skipping.

No further live SES or SMS send was performed for this review.

## Q. Repository and secret audit

| Check | Result |
|---|---|
| Branch | `ms1-auth-contact-verification` |
| MS2 work present | None |
| Unrelated files staged | None — `.idea/claudeCodeEditorTabs.xml` was deliberately excluded |
| Generated junk / logs / DB dumps / screenshots / temp test files | None in the commit; `backend/qa-tmp/` is git-ignored and has never been tracked |
| AWS keys, private keys in committed content | None |
| `OTP_PEPPER` / `JWT_SECRET` | Only the self-labelled development placeholders in `application.yml`, each refused at startup in a production-like environment by its own guard |
| Live-test PII (real recipient address, real handset number, real OTP) | None — every phone/email in code, tests and docs is a synthetic fixture (`+9725022345xx`, `@example.com`, `@demo.pronto.invalid`) |
| AWS account identifiers, ARNs, console detail | None |

**One environment finding, outside the repository and not a merge blocker.** A live AWS access key
pair is stored in plaintext in a local IntelliJ run configuration at `.idea/workspace.xml`. That path
is git-ignored (`.idea/.gitignore`), has never been tracked, and is not in any commit — so nothing
leaks through this merge. It is recorded here because `docs/architecture/hardening-plan.md` §2.5
already notes that the local development keys are **root-account** keys: they should be rotated to
scoped IAM credentials and moved out of the IDE configuration. Owner: MS4/MS5.

**One documentation inaccuracy, accepted.** `V47__harden_verification_codes.sql`'s column comment on
`code_hash` still says "SHA-256 hex" — true when V47 was written, superseded by Part 2's move to
keyed HMAC-SHA256. The migration is applied and forward-only, so it is not edited; correcting the
comment needs a V49, which is new work and out of scope for a Gate review. Functionally irrelevant:
the column width and contents are unchanged, and `OtpPepper` is the authority.

## R. Final state

**MS1 — `DONE`.** Tracker updated: MS1 `DONE`, **MS2 `NOT STARTED`**. MS2 was not begun in this
task, and no product work was introduced by it.
