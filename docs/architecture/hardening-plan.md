# Pronto — Milestone 7 Hardening & QA Pass: Concrete Plan

Status: **design pass for Milestone 7, ready for `pronto-qa`/`pronto-coding`**, written on
branch `MS7`, 2026-08-13. Written by `pronto-planning`. This is the executable spec
`pronto-qa` runs against and `pronto-coding` fixes findings against — it deliberately does
not restate the PRD, it defines exact tools, exact methodology, and exact pass/fail
thresholds for every item in scope. **Backend-only**, consistent with every prior
milestone — `frontend/` remains entirely deferred project-wide, not a Milestone-7-specific
cut.

Built from a full read of `docs/architecture/overview.md`,
`docs/architecture/implementation-plan.md` (all milestones, all "Known gaps" sections),
`docs/architecture/data-model.md` §3/§4, `docs/architecture/api-contract.md`,
`docs/architecture/api-contract-issues.md`, `docs/architecture/api-contract-bookings.md`,
`docs/architecture/api-contract-notifications.md`, and the actual backend source under
`backend/src/main/java/com/pronto/**` and `backend/src/main/resources/**` (all packages,
`application.yml`, all 14 Flyway migrations, `pom.xml`, `docker-compose.yml`) — findings
below are grounded in what is actually built, not restated from design docs alone.

**Environment ground truth this plan is written against** (stated explicitly since it
governs what can and can't be tested): there is **no deployed AWS environment anywhere in
this project's history**. Every milestone's QA to date was live-validated against a real
Postgres instance via `docker-compose up` + a locally-built/run jar (`mvnd clean package`)
— never against a real ECS/Elastic Beanstalk deployment, a real ALB/TLS termination, real
S3, or real OpenAI credentials. `S3StorageClient`/`OpenAiClassificationClient` compile and
are config-flag-activatable (`pronto.storage.mode=s3`, `pronto.ai.mode=openai`) but remain
live-untested, per Milestone 2's documented deferral. This plan does not pretend otherwise
— every item below states plainly whether it is a **live test** or a **config/design
review**, and never claims a live-tested result for something that structurally cannot be
live-tested in this environment.

---

## 0. How to read this document

- Every performance/security item below has a **Method** (exact tool/technique), a
  **Procedure** (exact steps), and a **Pass/fail threshold** (exact numbers). `pronto-qa`
  should be able to execute each item without needing to re-derive methodology.
- Findings that require a **product/architecture decision** (not a straightforward bug fix)
  are called out as such and are **not** silently resolved here — they're listed in §4 and
  cross-referenced in the final summary, pending `pronto-lead`/user sign-off, per the
  project's standing rule against silently picking an interpretation.
- §5 lists new risks/gaps found while reading the actual code for this pass, not previously
  documented anywhere else.

---

## 1. Performance validation plan (PRD §5.1)

### 1.0 Test environment (applies to every sub-item below unless stated otherwise)

- Real Postgres via `docker-compose up` (`postgres:16`, per `docker-compose.yml` — not
  H2/an in-memory substitute).
- Backend built via `mvnd clean package` and run as a standalone jar (`java -jar
  target/pronto-backend-*.jar`), **not** run inside an IDE debugger — debugger
  instrumentation overhead would skew latency measurements.
- Config: `pronto.storage.mode=local`, `pronto.ai.mode=mock`, `pronto.email.mode=log`
  (the only live-tested configuration for any milestone to date — consistent with that,
  not a new limitation introduced here).
- Every measurement run is preceded by a 10-request warm-up (discarded, not counted) so
  JIT/connection-pool warm-up doesn't distort p95/p99 numbers.
- All timings are wall-clock, client-observed (request-sent → full-response-received), via
  either `curl -w '%{time_total}\n'` for single-shot sequential measurements or k6's
  built-in `http_req_duration` metric for concurrent/scripted runs (tool choice justified
  in §1.3).

### 1.1 PRD §5.1.1 — Maximum screen loading time: 2 seconds

**Not fully verifiable this milestone — stated explicitly, not papered over.** There is no
frontend anywhere in this project (a project-wide deferral, not specific to Milestone 7),
so there is no actual "screen" to load and no way to measure real render time, bundle
load, or the network hop a real browser would add. What follows is a **backend-only proxy**
for this requirement: a response-time budget for the API call(s) that would populate each
screen once a frontend exists, so that a real frontend later has a known, tested backend
floor to build on rather than an unknown one.

**Method**: sequential `curl -w '%{time_total}'` timing, N=20 per endpoint (after warm-up),
against a fully migrated + representatively-seeded local Postgres instance (seed volume:
same seed set used for §1.3's load test, so list-endpoint sizes are realistic rather than
near-empty).

**Screen → backing endpoint(s) mapping and budget:**

| Screen (per `overview.md` §4 `frontend/src/features/*`) | Backing endpoint(s) | Backend budget (p95, this test) |
|---|---|---|
| Login / app-load "who am I" | `GET /api/users/me` | ≤ 500ms |
| AI Review (post-classify) | `POST /api/issues/classify` | ≤ 500ms (mock AI client; real OpenAI latency is untested, see §1.1 note below) |
| Issue detail / booking confirmation | `GET /api/issues/{id}` | ≤ 500ms |
| Standard professional list | `GET /api/bookings/professionals?issueId=` | ≤ 500ms |
| Standard slot picker | `GET /api/bookings/professionals/{id}/slots?issueId=` | ≤ 500ms |
| SOS professional list | `GET /api/bookings/sos-professionals?issueId=` | ≤ 500ms |
| Tracking screen | `GET /api/bookings/orders/{orderId}` | ≤ 500ms |
| Notification bell/feed | `GET /api/notifications` | ≤ 500ms |
| Professional dashboard — incoming requests | `GET /api/bookings/orders/me?status=PENDING` | ≤ 500ms |
| Professional dashboard — availability | `GET /api/availability/slots/me`, `GET /api/availability/sos-availability` | ≤ 500ms each |

**Reasoning for the 500ms figure**: out of the PRD's 2000ms total screen-load budget, a
generous allowance is reserved for parts this test cannot measure (real network round-trip
in a deployed environment, frontend JS bundle load/hydration, render) — 500ms backend
response time leaves roughly 1500ms of headroom for those once they exist, while still
being a meaningful, enforceable backend-only signal today.

**Re-run under load**: the same endpoints, same budget category, are re-measured as part of
§1.3's k6 load-test scenario (not a separate run) with a relaxed threshold of **p95 ≤
800ms** under concurrent load — the PRD's 2s target must still plausibly hold when the
system is busy, not only when idle.

**Pass/fail**: every endpoint in the table meets its quiescent (≤500ms) and
under-load (≤800ms, §1.3) p95 threshold. **Explicit, permanent caveat**: this sub-item can
never be marked "PRD §5.1.1 fully verified" until a real frontend exists and is measured
end-to-end in a real deployed environment — `pronto-qa`'s sign-off report must state the
proxy-only nature of this result, not claim the literal requirement is met.

### 1.2 PRD §5.1.2 — Maximum response time for a status update: 1 second

Two distinct, both-necessary parts, because "status update response time" has two
different valid readings and this plan tests both explicitly rather than picking one
silently.

**Part A — raw endpoint latency (the literal "the API call responds within 1s" reading).**

**Method**: `curl -w '%{time_total}'`, N=20 per endpoint, quiescent system + re-measured
under §1.3's load test.

**Endpoints**: every order-status-mutating endpoint (`POST
/api/bookings/orders/{orderId}/accept`, `.../reject`, `.../cancel`, `.../on-the-way`,
`.../complete`) and the two read endpoints a polling client hits (`GET
/api/bookings/orders/{orderId}`, `GET /api/notifications`).

**Pass/fail**: p95 < 1000ms for every endpoint, both quiescent and under §1.3's load-test
concurrency. This is the directly-testable, literal component of PRD §5.1.2 and the only
part of this sub-item allowed to be reported as "verified" without qualification.

**Part B — end-to-end propagation simulation (the "client observes the change" reading,
already analyzed qualitatively in `api-contract-notifications.md` §4.5, now given a
concrete test).**

**Method**: a small script (bash + `curl`, or a short k6 scenario) that:
1. Creates a real order (via the real API, e.g. `POST /api/bookings/orders`) and records
   its id.
2. Starts a background loop polling `GET /api/bookings/orders/{orderId}` every 4s
   (mid-point of the documented 3–5s client polling interval, `overview.md` §3.3),
   recording `orderStatus`/`updatedAt` and a local timestamp on every response.
3. At an arbitrary point after the loop starts, fires the professional's `accept` call,
   recording wall-clock `T0` immediately before sending it.
4. Records `T1` = the timestamp of the first subsequent poll response whose `orderStatus`
   reflects `CONFIRMED`.

**Pass/fail**: `T1 - T0` ≤ 6000ms (5s worst-case polling interval + 1s worst-case endpoint
latency from Part A). **This number is a known, already-documented, already-accepted
design consequence of the short-polling architecture decision (`overview.md` §3.3), not a
defect if it lands at ~5-6s** — Part B's purpose is to confirm the *actual* observed
number matches this expectation (i.e., nothing is silently adding extra latency beyond the
polling interval itself), not to demand sub-1s end-to-end propagation, which the
short-polling design was never intended to deliver and which Part A already covers
correctly (the literal per-call response-time requirement).

**Also explicitly re-confirm, not re-litigate**: the `OrderExpirySweepJob`'s 60s detection
interval (for `PENDING → EXPIRED`) is a *detection*-latency concern, categorically separate
from PRD §5.1.2's *propagation*-latency target, per `api-contract-notifications.md` §4.5's
already-settled reasoning. This plan does not re-test or re-argue that conclusion — it is
carried forward as settled, not open.

### 1.3 PRD §5.1.3 — 1,000 concurrent users

**Tool: k6.** Chosen over JMeter/Gatling for this environment specifically: k6 is a single
static binary (no JVM), so running the load generator alongside the Spring Boot JVM and
Postgres on the same dev laptop doesn't have the load tool itself competing significantly
for the same JVM/heap resources it's trying to measure — JMeter/Gatling's own JVM overhead
would more meaningfully skew results on a single machine running everything. k6 scripts are
plain JavaScript, easy to version-control alongside this doc if `pronto-qa` wants to check
the script in later.

**Install** (Windows, per this environment): `choco install k6` or download the release
binary directly — no other setup required.

**Seed data** (via a setup script hitting the real registration/issue-creation endpoints,
or direct SQL insert matching the schema — either is acceptable, but registration-endpoint
seeding is preferred so professional/customer accounts are realistically shaped, e.g. each
professional has its `sos_availability` row created the normal way): professionals spread
across all 8 categories with several `availability_slots` each and roughly half toggled
`sos_availability.is_available = true`; a pool of customer accounts each with several `OPEN`
issues across categories (large enough that the load test's booking-action traffic doesn't
exhaust the pool of bookable issues mid-run — recommend at least 500 pre-seeded `OPEN`
issues). Seeding is a setup-only step, excluded from the measured scenario itself.

**Scenario design** (weighted to reflect realistic v1.0 traffic — short-polling reads
dominate, not writes):

| Traffic share | Behavior |
|---|---|
| 70% of VU time | Authenticated polling loop: `GET /api/bookings/orders/{orderId}` (or `GET /api/notifications`) every 3–5s (jittered), repeated for the VU's session |
| 15% | Browsing: `GET /api/bookings/professionals?issueId=` / `GET /api/bookings/sos-professionals?issueId=` |
| 10% | Booking actions against pre-seeded `OPEN` issues: `POST /api/bookings/orders` / `POST /api/bookings/sos-orders` / `accept` / `reject` / `cancel` / `on-the-way` / `complete` |
| 5% | Auth: `POST /api/auth/login` once per VU at session start (not per-iteration) |

**Ramp profile** (k6 `stages`), run at two distinct scales:

- **Scale A — "baseline," required to pass**: ramp 0 → 150 VUs over 2 minutes, hold 150 VUs
  for 5 minutes, ramp down over 1 minute.
- **Scale B — "ceiling probe," informational only, not a hard pass/fail gate**: continue
  ramping past 150 (e.g., 150 → 400+) until either the error-rate or latency threshold
  below is breached, to document this local environment's actual practical ceiling.

**Why 150, not literally 1,000 — reasoning, not an arbitrary downgrade**: this test runs
the load generator, the Spring Boot JVM, and Postgres all on one shared dev machine, with
no load balancer, no horizontal backend scaling, and none of the real network
latency/geography the PRD's 1,000-concurrent-user claim implicitly assumes (a real
deployment behind an ALB/CDN with TLS termination and possibly multiple backend
instances). Past some VU count on a single laptop, the load generator itself becomes the
bottleneck rather than the backend under test, making the result meaningless as a signal
about the backend. 150 concurrent short-polling users (roughly 30-40 req/s aggregate at
150 VUs polling every 3-5s, plus browsing/booking overhead) is a scale that still exercises
genuine contention this codebase actually has to get right — DB connection-pool pressure,
row-lock contention on the guarded `UPDATE ... WHERE <state>` transitions on
`orders`/`availability_slots`/`sos_availability` (§3.2 of `api-contract-bookings.md`) — while
remaining achievable and trustworthy as a signal on a single dev laptop. **This explicitly
validates the application/DB layer's concurrency correctness and rough throughput
headroom, not the full real-AWS 1,000-concurrent-user claim.**

**Pass/fail thresholds (Scale A, N=150, required to pass)**:

- **Zero HTTP 5xx responses** over the full run. A `409` from a lost booking/slot/order-state
  race (e.g. two VUs racing to book the same slot) is an **expected, correct** business
  response under concurrent load, not a failure — explicitly excluded from the error-rate
  count in the k6 threshold config, not conflated with a real defect.
- **p95 < 1000ms, p99 < 2000ms** on `http_req_duration`, checked per named request group
  (polling reads, browsing reads, booking-action writes, auth) — not just an aggregate
  figure, so a single slow endpoint category can't be masked by fast ones.
- **Zero Postgres connection-pool exhaustion** — grep backend logs for `HikariPool-1 -
  Connection is not available` (or equivalent); any occurrence is a fail, and the default
  Spring Boot HikariCP pool size (10) becomes a documented tuning candidate for
  `pronto-coding` if hit, not evidence of a code defect by itself.
- **Zero unexpected DB constraint-violation 500s** — any `500` traced back to a DB
  `CHECK`/unique-constraint violation surfacing through `GlobalExceptionHandler`'s catch-all
  (rather than a clean `409` from a guarded-`UPDATE` race) indicates a genuine race-condition
  bug in the guarded-transition pattern that MS3-6's QA didn't catch under real concurrency
  — a fail, and a `pronto-coding` bug-fix candidate, not a load-test infrastructure issue.
- **Backend process does not crash/OOM** during the run (monitor via `docker stats` /
  Task Manager / JVM heap logging).

**Deliverable**: k6's JSON/HTML summary output archived alongside the QA sign-off notes,
plus one paragraph reporting the Scale B ceiling probe's result as an informational data
point (not a pass/fail gate).

**Explicit, permanent caveat** (restated per the task brief's instruction): this test
validates the application/database layer's behavior under realistic concurrent traffic on a
single local machine. It is **not** equivalent to, and must not be reported as, a validated
"the real deployed system supports 1,000 concurrent users" claim — no load balancer, no
horizontal backend scaling, no real network latency exists in this test. Full validation of
the literal PRD requirement is only possible once a real AWS deployment exists.

### 1.4 PRD §5.1.4 — Maximum image upload time: 5 seconds

**Method**: `curl -w '%{time_total}'` uploading real JPEG files to `POST
/api/storage/images` with a valid customer JWT, `pronto.storage.mode=local` (the only
live-tested storage mode to date).

**File sizes tested**: 200 KB, 1 MB, 4 MB, 7.9 MB (just under the 8 MB
`spring.servlet.multipart.max-file-size` cap, `application.yml`).

**Procedure**: N=20 sequential uploads per size (quiescent baseline) **and** N=20
concurrent uploads per size via a short k6 scenario (to catch the "5s under load" case,
since image uploads plausibly occur during the same busy periods the §1.3 load test
models).

**Pass/fail**: p95 total time (request sent → `201` response received) < 5000ms for every
tested size, both quiescent and concurrent.

**Boundary sanity check** (not itself a performance requirement, but cheap to verify
alongside this test): a file just over 8 MB correctly returns `413 IMAGE_TOO_LARGE`
quickly (not a slow timeout) — confirms Spring's `MaxUploadSizeExceededException` guard
fires before attempting to buffer/process the oversized body.

**Explicit gap, inherited from Milestone 2, not resolved by this pass**: this test only
validates the `pronto.storage.mode=local` (backend-proxied, local-disk) path.
`S3StorageClient` remains untested end-to-end (no AWS credentials available in this
environment, per every prior milestone's documented deferral) — the 5s target against real
S3 upload latency is genuinely unverified. If AWS credentials become available before
deployment, re-run this exact test with `pronto.storage.mode=s3` before relying on the
result for a real deployment.

---

## 2. Security checklist (PRD §5.2)

### 2.1 PRD §5.2.1 — All communication shall use HTTPS/TLS 1.3

**Not live-testable this milestone — stated explicitly.** TLS termination is an
infrastructure/deployment-layer concern (`overview.md` §3.7: "terminated at the load
balancer/CDN layer"), and no such deployed environment exists anywhere in this project.
This item is a **config/design-level compliance review**, not a live TLS handshake test —
`pronto-qa`'s sign-off must record it as such, not claim a live result that cannot exist
yet.

**Checklist** (code/config review, executable now):

- [ ] Confirm no application code path hardcodes or otherwise assumes a plaintext `http://`
  scheme for anything the app itself controls in production. **Reviewed**: the only
  `http://` literal found is `pronto.storage.public-base-url`'s default,
  `http://localhost:${server.port:8080}` (`application.yml`) — explicitly documented as
  local-dev-only and overridable via the `STORAGE_PUBLIC_BASE_URL` env var. Confirm this
  default is never relied upon outside local dev/QA before any real deployment.
- [ ] Confirm `SecurityConfig` contains no logic that would conflict with TLS terminating
  upstream of the application (e.g. a hardcoded `requiresChannel().requiresSecure()` that
  would reject the plain-HTTP traffic an ALB/CDN forwards internally after terminating TLS
  itself). **Reviewed**: no such logic exists — clean.
- [ ] Re-confirm with `pronto-lead`/user that `overview.md` §3.7's stated deployment
  architecture (TLS termination at the load balancer/CDN layer, backend receives plain HTTP
  internally) is still the intended design — this doc cannot verify that against a real
  deployment, only restate that it's still the documented plan and nothing in the code
  contradicts it.
- [ ] **Recommendation for the eventual deployment milestone** (not actionable in M7
  itself, no environment to apply it to yet): add `server.forward-headers-strategy:
  framework` to `application.yml` once behind a real ALB, so Spring correctly interprets
  `X-Forwarded-Proto`/`X-Forwarded-For`. Low urgency today since auth is stateless
  bearer-token (not cookie-based), so there's no secure-cookie-flag correctness issue
  riding on this — flagged for completeness, not urgency.

**Verdict this item must be recorded with**: "config/design reviewed, compliant by
inspection — not live-tested, no deployed environment exists to test against."

### 2.2 PRD §5.2.2 — Passwords shall be stored in encrypted form

**Live test + code review.**

- [ ] **Code review, confirmed**: `SecurityConfig.passwordEncoder()` wires
  `BCryptPasswordEncoder` (Spring Security default strength 10) as the sole encoder;
  `AuthService.register`/`.login` route every password through it — no plaintext path
  exists.
- [ ] **Live regression test**: register a new account, `psql`-query `users.password_hash`
  directly, confirm it matches the bcrypt format (`$2a$10$...`/`$2b$10$...`, 60 characters)
  and is never equal to the submitted plaintext password.
- [ ] **Code review, confirmed clean**: grepped the codebase for any log statement that
  could print a raw password or an entire `LoginRequest`/`RegisterRequest` object — none
  found. **Standing risk worth flagging explicitly**: both are Java `record`s, so their
  auto-generated `toString()` includes the raw plaintext `password` field; if any future
  code ever logs one of these request objects directly (e.g. `log.debug("{}", request)` for
  troubleshooting), it would leak plaintext passwords into logs. Not a current defect — a
  standing convention `pronto-coding` should keep respecting, worth a one-line comment on
  both DTOs if `pronto-coding` wants to make the risk self-documenting.
- [ ] **Code review, confirmed clean**: no response DTO (`RegisterResponse`,
  `LoginResponse`, `UserMeResponse`) ever echoes back the plaintext or hashed password.

### 2.3 PRD §5.2.3 — Lock a user account after 5 failed login attempts

**Live regression test** (re-running Milestone 1's original scenario, not a new design
question — Milestone 1 already validated this; this pass re-confirms no regression across
MS2-6):

- [ ] 4 consecutive wrong-password attempts on a real account → `users.failed_login_attempts`
  increments to 4 (`psql`-verified), `locked_until` still `NULL`.
- [ ] 5th wrong attempt → `423 ACCOUNT_LOCKED`, `locked_until` set to ~15 minutes out.
- [ ] A 6th attempt, this time with the **correct** password, while still within the lock
  window → still `423 ACCOUNT_LOCKED` (password is never checked while locked, per
  `AuthService.login` step 2) — `psql`-verify `failed_login_attempts` did **not** increment
  further (confirms the "a locked-out user hammering login doesn't extend their own
  lockout" behavior).
- [ ] Backdate `locked_until` via direct SQL (same technique Milestone 5 QA used for the
  expiry sweep, faster than waiting 15 real minutes) to simulate window expiry; confirm the
  next login attempt resets `failed_login_attempts` to 0 and, with correct credentials,
  succeeds normally.

**Related finding, not a defect in this mechanism, cross-referenced to §5**: lockout is
correctly per-account (`users.locked_until`), exactly as designed — but there is no
IP-based or global rate limiting on `/api/auth/login` (or `/register`/`/verify`) at all, so
an attacker distributing guesses across many different accounts, or many different
passwords tried at a rate that stays under 5-per-account, is not mitigated by this
mechanism. See §5 for the full writeup — flagged as a new risk, not folded into this
checklist item since PRD §5.2.3 only asks for the per-account lockout, which is correctly
implemented.

### 2.4 PRD §5.2.4 — Account deletion and personal data management

**Live regression test** (re-running Milestone 1's scenario):

- [ ] `DELETE /api/users/me` with a valid token → `204`. `psql`-verify: `deleted_at` set,
  `full_name` = `'Deleted User'`, `email` = `deleted-user-{id}@pronto.invalid`.
- [ ] The very next request with the same (still-unexpired) token → `401 UNAUTHORIZED`
  (revocation-via-`deleted_at`-check working, per `api-contract.md` §3.1).
- [ ] The original (now-freed) email address is immediately usable for a fresh registration.
- [ ] **New this pass — live-verify an item that was only cross-referenced as a
  dependency before, not independently confirmed end-to-end**: delete a **professional**
  account, then confirm that professional no longer appears in `GET
  /api/bookings/professionals?issueId=` or `GET /api/bookings/sos-professionals?issueId=`
  for a matching-category issue. (`api-contract.md` §2.5 originally flagged this as a
  dependency on the Milestone 3/4 listing queries; `api-contract-bookings.md` §2.2 step 7 /
  §2.12 step 7 confirm the `users.deleted_at IS NULL` join was in fact built — this test
  closes the loop by verifying it live rather than trusting the cross-reference alone.)

**DECIDED — soft-delete + anonymization is the accepted MVP answer; data export is
deferred backlog, not an open question.** The user has ruled: the already-built,
already-QA-verified soft-delete + PII-anonymization behavior above (including the
professional-listing-exclusion check) is sufficient to satisfy PRD §5.2.4's "personal data
management" clause for MVP/Milestone 7. A self-service data-export/access endpoint (e.g.
"download everything Pronto has about me") is **not** built and is **not** Milestone 7
scope — it is reclassified as a genuine, acknowledged backlog item for a possible future
version, not an open question blocking anything today.

### 2.5 AWS credential hygiene — unsafe technical debt, MUST be resolved before any deployment

**Finding**: the local IntelliJ run configuration (`.idea/workspace.xml`) currently injects
a live `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` pair belonging to the AWS **root
account** (ARN `arn:aws:iam::038832651513:root`), not a scoped IAM user or role.
**Confirmed not a repository leak**: `.idea/.gitignore` excludes `/workspace.xml`, and
`git ls-files .idea/` confirms it is not tracked — this is a local-developer-machine
exposure only, not a committed secret.

**Why this matters regardless of the "not committed" fact**: root-account access keys carry
unrestricted, account-wide permissions (billing, IAM user/policy management, every AWS
service) — categorically broader than anything `S3StorageClient` actually needs
(`s3:PutObject`/`s3:GetObject`/`s3:HeadObject` on one bucket). If this key pair were ever
exposed by any other means (a misconfigured client, a support-ticket paste, a future
accidental commit of a `.env` file, etc.), the blast radius is the entire AWS account.

**Required before any production/deployment — not a soft recommendation, not the user's
discretionary call on *whether*, only on exact timing.** These root-account keys **must**
be rotated to a scoped IAM user (or, better, an IAM role assumed by the ECS/Elastic
Beanstalk task once a real deployment exists, avoiding long-lived keys entirely) with a
minimal policy limited to `S3StorageClient`'s actual bucket/actions, and the current root
access key pair **must** be deactivated after rotating (AWS's own guidance is that root
access keys generally shouldn't exist at all). **Root-account keys must never be used
beyond local development.** This is unambiguous, required-before-deployment language,
matching this project's existing convention for stating hard requirements elsewhere (e.g.
`overview.md`'s "hard exclusion" framing for the payment-processing/GPS out-of-scope items,
and the checked-in `JWT_SECRET` default's own "must be overridden ... before any real
deployment" framing/enforcement, §5.1's `JwtSecretStartupGuard`). No application code needs
to change — `S3StorageClient` picks up credentials purely via the AWS SDK's
`DefaultCredentialsProvider` env-var chain (`S3StorageClient.java`, constructor), so a
credential rotation is a pure infrastructure/local-config action. **No infrastructure
change was made by this documentation pass** — no AWS CLI command was run, no credential
file was touched, `.idea/workspace.xml` was not modified; only this document's language was
strengthened, per explicit instruction. The exact timing of the actual rotation remains the
user's own future action.

---

## 3. Cross-flow regression test matrix (PRD §9 Must-Have / Should-Have)

Every flow below must be re-verified end-to-end against a real Postgres instance (not unit
tests alone), matching the rigor every milestone's own QA pass already established. This is
the full-system regression `implementation-plan.md`'s Milestone 7 acceptance criteria
("QA sign-off against every PRD Must-Have and Should-Have requirement... no known critical
defects open") refers to.

| # | Flow | PRD priority | Built in | Key endpoints | Regression checklist |
|---|---|---|---|---|---|
| 1 | Registration, verification, login, lockout, account deletion | Foundational (underlies every Must/Should-Have flow) | MS1 | `/api/auth/*`, `/api/users/me` | Full MS1 QA scenario re-run (happy path both roles, lockout §2.3 above, deletion §2.4 above, case-insensitive duplicate email, expired/consumed verification codes) |
| 2 | Issue creation (**Must-Have**) | Must-Have | MS2 | `POST /api/issues/classify`, `POST /api/issues`, `GET /api/issues/{id}` | Upload 0-6 images → classify (no DB write, repeatable) → confirm/override category → create → fetch back; invalid `categoryId`; cross-customer image-key rejection |
| 3 | Image upload (**Should-Have**) | Should-Have | MS2 | `POST /api/storage/images`, `GET /api/storage/images/**` | Upload/retrieve round-trip byte-identity (local mode); unsupported content-type `400`; oversized file `413`; ownership enforcement on retrieval; timing re-verified per §1.4 |
| 4 | Standard path + professional selection (**Must-Have** x2) | Must-Have | MS3 | `GET /api/bookings/professionals`, `GET .../slots`, `POST /api/bookings/orders`, `accept`/`reject`/`cancel`, `GET .../{orderId}`, `GET .../me` | Full happy path with row-level DB verification at each step; reject → return-to-list → re-book same professional; full cancel actor/state matrix (§2.7); soft-deleted professional excluded from listings (re-verify per §2.4 above) |
| 5 | SOS path with customer choice (**Should-Have**) | Should-Have | MS4 | `GET /api/bookings/sos-professionals`, `POST /api/bookings/sos-orders`, `PUT`/`GET /api/availability/sos-availability` | SOS listing reflects live toggle; SOS order creation; "becomes unavailable" `409 SOS_PROFESSIONAL_UNAVAILABLE` branch; empty-list-is-valid-response (no-professional-available case); cross-path `ISSUE_URGENCY_MISMATCH` both directions |
| 6 | Real-time status updates (**Should-Have**) | Should-Have | MS5 | `GET /api/notifications`, `POST /{id}/read`, `POST /read-all`, `EmailDispatchJob`, `OrderExpirySweepJob` | Notification created on every transition with correct recipient (re-verify the full trigger→recipient table, `api-contract-notifications.md` §4.2, including the new MS6 `ON_THE_WAY`/`COMPLETED` hooks); unread count/read/read-all correctness; email dispatch job transitions `PENDING → SENT` within ~20s; expiry sweep boundary correctness (both Standard 15min and SOS 5min cutoffs, "lost the race" case); propagation timing re-verified per §1.2 |
| 7 | Professional dashboard — job-status progression | Built, supports Should-Have #6's real-time-status value | MS6 | `POST .../on-the-way`, `POST .../complete` | Full `PENDING → CONFIRMED → ON_THE_WAY → COMPLETED` sequence both booking types; skip-ahead (`CONFIRMED → COMPLETED` direct) correctly `409`s; actor/ownership enforcement; `issues.status → COMPLETED` only on final `complete` call, never earlier |
| 8 | Professional dashboard — availability management & incoming requests | Built, supports Must-Have #4/#5 | MS3/4/6 | `POST`/`GET /api/availability/slots(/me)`, `PUT`/`GET /api/availability/sos-availability`, `GET /api/bookings/orders/me?status=PENDING` | Slot creation/listing; SOS toggle idempotency; incoming-requests query filters correctly by status and caller identity |
| 9 | Cross-milestone regressions | N/A — full-system sanity | All | `/actuator/health`, all of the above | Confirm no endpoint from any earlier milestone has regressed as a side effect of a later one's changes (mirrors every prior milestone's own closing regression pass, e.g. MS6's explicit re-verification that the MS5 expiry sweep still correctly ignores `CONFIRMED`/`ON_THE_WAY`/`COMPLETED` orders) |

**Not in this matrix, confirmed correctly excluded**: payment processing and GPS/live
location tracking remain permanent v1.0 exclusions (`overview.md` §2), not regression
targets. Internal chat and additional-language support are PRD §9 Nice-to-Have, explicitly
out of scope, no action needed.

---

## 4. Known-gap triage — recommendations only, pending `pronto-lead`/user sign-off

Four items carried forward from Milestones 5/6's "Known gaps" sections. Each gets a
recommendation below on whether it is Milestone-7-hardening-appropriate or should stay
explicitly deferred as out-of-scope feature work. **None of these are decided by this
document** — they are recommendations for `pronto-lead`/the user to rule on before
`pronto-coding` acts on any of them.

### 4.1 The `EXPIRED`-issue-cannot-be-rebooked gap — DECIDED (final, permanent design)

(`data-model.md` §4, `api-contract-notifications.md` §7, restated unchanged through MS6.)

**Decided by the user, 2026-08-15 — option 3 of the three originally listed below, and
this is now the settled, permanent v1.0 design, not a placeholder pending a future
decision.** `EXPIRED` remains a final `issues.status` state permanently: there is no
reopen endpoint and no relaxed booking guard on `createOrder`/`createSosOrder`. The
intended, permanent flow for a customer who wants service again after their issue expires
is to create a new issue (`POST /api/issues`). The three options originally on the table:
1. Add a "reopen" endpoint (`EXPIRED → OPEN`). **Not chosen.**
2. Treat `EXPIRED` issues as book-able too (relax the `status == 'OPEN'` guard on
   `createOrder`/`createSosOrder`). **Not chosen.**
3. Accept "create a new `issues` row for the same problem" as the permanently-intended
   workaround, and consider this gap closed by design rather than open. **Chosen — this is
   the final decision.**

No code change results from this ruling — the existing behavior (`409
ISSUE_NOT_BOOKABLE` on an `EXPIRED` issue, already implemented and already tested) was
already correct; only the documentation's framing changes, from "open product question
pending sign-off" to "intentional, permanent design," across every doc that previously
described this as unresolved (`data-model.md` §4, `api-contract-notifications.md` §7,
`api-contract-bookings.md` §9, and this document's own §6 item 1).

### 4.2 No retry/backoff for `FAILED` email rows (`EmailDispatchJob`) — CONFIRMED DEFERRED, not Milestone 7 scope

**Confirmed deferred, with the load-bearing check explicitly done — not a pending "needs
confirmation" item.** Verified directly by reading `EmailDispatchJob.java` for this closing
pass: it only ever runs against `LoggingEmailSender`, which logs at `INFO` and performs no
real I/O — it never throws for transient-network reasons, so a `FAILED` row under the
current configuration can only arise from a genuine application-level bug (e.g. a missing
recipient user), never a retryable transient failure. **Neither gap is load-bearing for
correctness of any currently-existing behavior — confirmed, not assumed.** Building and
testing retry logic against a sender that essentially never fails would not meaningfully
harden anything real — the actual failure modes retry/backoff exists to guard against (SMTP
timeouts, provider rate limits, etc.) don't exist until a real SMTP/SES `EmailSender`
implementation is built, which is itself still undecided (`api-contract-notifications.md`
§6 item 3). **Backlog entry**: revisit this specific gap at the same time a real
SMTP/SES email provider is chosen and implemented, not before.

### 4.3 No multi-instance email-dispatch atomic "claim" step — CONFIRMED DEFERRED, not Milestone 7 scope

**Confirmed deferred, with the load-bearing check explicitly done.** `overview.md` §6
already states the managed-container/multi-instance deployment question is "not yet
confirmed as needed" for v1.0, and this project has never run more than one backend
instance in any environment to date (local, single jar, every milestone) — reconfirmed for
this closing pass, not merely assumed. Building an atomic per-row claim mechanism (which
`api-contract-notifications.md` §4.4 already scoped as needing an interim `delivery_status`
value and a new `V15` migration) is infrastructure for a horizontal-scaling scenario this
project doesn't currently have — premature per the project's own stated "don't
over-engineer" guidance, and **confirmed not load-bearing for correctness of any
currently-existing behavior**. **Backlog entry**: revisit if/when a real multi-instance
deployment is actually planned, at which point it becomes a concrete, scoped, one-migration
fix, not a speculative one.

### 4.4 Slot edit/delete in `availability` — REVERSED during this Milestone 7 pass, now designed, built, and QA-passed

**Superseded.** This section originally recommended leaving `availability` without slot
edit/delete alone, reaffirming Milestone 6's judgment call (`api-contract-bookings.md`
§8.2's original reasoning: no PRD text mandates it, no functional flow was blocked without
it, frontend remains deferred project-wide). **That recommendation has since been overruled
by an explicit user product decision, made and acted on during this same Milestone 7
hardening pass** — not a re-evaluation of the original reasoning's merits, which remained
defensible at the time it was made (see `api-contract-bookings.md` §8.2's preserved
historical text for the full record of the reversal).

**What actually happened, for the record**: `PUT`/`DELETE /api/availability/slots/{slotId}`
(`api-contract-bookings.md` §2.18/§2.19) were designed, implemented, and QA-passed this
session — full regression plus new targeted tests, zero gaps found, including live
verification across all four order lifecycle states
(`PENDING`/`CONFIRMED`/`ON_THE_WAY`/`COMPLETED`) for the booking-protection guard
(`is_available = true` required, `409 SLOT_IN_USE` otherwise — never a silent no-op, never
a cascade-cancel of the order the slot backs). See `api-contract-bookings.md` §8.2 and
`backend/src/main/java/com/pronto/availability/README.md` for full detail, not restated
here.

This item is **closed, not open** — no further action or sign-off needed.

---

## 5. New risks/gaps found while reading the code for this pass

Not previously documented anywhere else — surfaced specifically by reading
`backend/src/main/java/com/pronto/**`, `application.yml`, and the local dev environment for
this hardening pass.

### 5.1 Checked-in insecure default `JWT_SECRET` — real risk if a real deployment ever forgets to override it

`application.yml`:
```yaml
pronto:
  jwt:
    secret: ${JWT_SECRET:local-dev-only-insecure-jwt-secret-key-please-override-via-JWT_SECRET-env-var-before-any-real-deployment}
```

The comment correctly documents this as local-dev-only and not production-safe — but
nothing in the application actually **enforces** that at runtime. If a real deployment ever
boots without setting the `JWT_SECRET` env var (a config-management mistake, not a code
bug), the application would silently start up successfully and issue JWTs signed with this
publicly-known-from-the-repo string — every token would be trivially forgeable by anyone
who has read this file (which, being open-source-shaped and version-controlled, is anyone
with repo access, now or in the future). **Recommendation (not implemented here, a
`pronto-coding` candidate for this milestone)**: add a startup-time check (e.g. a
`@PostConstruct` in `JwtService` or an `ApplicationRunner`) that fails fast — refuses to
start — if the active Spring profile is anything other than a recognized local-dev profile
and `pronto.jwt.secret` still equals this exact placeholder string. Cheap, high-value,
directly closes a real (if currently dormant) risk.

### 5.2 No IP-based/global rate limiting on `/api/auth/login`, `/register`, `/verify`

Cross-referenced from §2.3 above. Account lockout (PRD §5.2.3) is correctly implemented
per-account, but there is no mechanism anywhere in the codebase (confirmed — no rate-limiting
library, filter, or bucket/counter mechanism exists in `pom.xml` or any `auth`-package
class) limiting request volume by IP or globally. Two concrete exposures this leaves open:
- **Distributed credential-stuffing**: an attacker trying one or two guesses per account
  across a large list of email addresses never triggers any single account's 5-attempt
  lockout.
- **Verification-code brute-forcing**: `POST /api/auth/verify`'s 6-digit numeric code (1 in
  1,000,000 per guess) has no attempt cap at all — nothing stops an unlimited number of
  guesses within the code's 15-minute validity window for a known email address.

**Not scored as a hard Milestone-7 blocker** (no source document requires IP-based rate
limiting, and this is consistent with the project's already-accepted "no rate limiting on
`/classify`" stance from Milestone 2, `api-contract-issues.md` §4) — but flagged here
explicitly as a real, previously-undocumented gap, since it's materially different in kind
from the already-flagged `/classify` cost-exposure concern (this one is a security
exposure, not a cost one). **Recommendation**: candidate for a lightweight fix this
milestone if time allows (e.g. a simple in-memory or DB-backed per-IP counter on
`/api/auth/*`, no new infrastructure needed at this traffic scale) — but explicitly a
`pronto-lead`/user call on priority, not assumed.

### 5.3 `api-contract-issues.md` §4's "S3 bucket-privacy — genuinely undecided" note is stale; the real code already resolved it

Documentation-drift finding, not a code defect. `api-contract-issues.md` §4 still describes
the S3 image-privacy question as "genuinely undecided," but the actual, already-implemented
`S3StorageClient.java` Javadoc states the decision was made and built: the bucket blocks
all public access, and every image fetch (both `local` and `s3` storage modes) is
backend-proxied through `GET /api/storage/images/**` rather than a public/pre-signed S3
URL. **Recommendation**: flag for `pronto-documentation`'s next pass to update
`api-contract-issues.md` §4 to reflect this as resolved (cross-referencing
`S3StorageClient.java`'s Javadoc) rather than leave it listed as an open question it no
longer is — not fixed in this document per the standing convention of flagging cross-doc
edits rather than making them here.

### 5.4 No CORS configuration anywhere in `SecurityConfig`

Forward-looking gap, not a current v1.0 blocker (no frontend exists to be blocked yet), but
worth surfacing now so it isn't rediscovered as a surprise once frontend work actually
starts: `SecurityConfig.java` configures no CORS policy at all. Once a real React frontend
exists on a different origin than the backend (the typical dev setup, e.g.
`localhost:5173` calling `localhost:8080`, or a real deployed frontend origin calling a
separate backend origin), browsers will block cross-origin requests by default without an
explicit CORS policy. **Recommendation**: not Milestone 7 scope (no frontend to test
against yet), but flag as a required first step of whichever milestone starts real frontend
integration work, so it isn't discovered as a last-minute blocker then.

### 5.5 No pagination on any list endpoint — CONFIRMED STILL DEFERRED, re-confirmed during Milestone 7's closing pass

Already flagged as a Milestone 7 hardening candidate in `api-contract-bookings.md` §7 ("flag
as a Milestone 7 hardening candidate if professional/slot/order counts ever grow large
enough to matter") and `api-contract-notifications.md` §7. §1.3's load test supplied the
real measured data point this section originally asked for: the professionals-listing
payload measured approximately 1565 bytes for about 7 professionals in one category at
current seed scale — comfortably within the "tens of KB, defer" range this section itself
set as its own decision threshold. **Re-confirmed still deferred during Milestone 7's
closing documentation pass, not left open by default** — a fresh check found zero accidental
partial pagination implementation anywhere in the codebase (no `Pageable`/`Page<`/
`pagination`/`PageRequest` usage in any application source file; only doc/README mentions
discussing it as deferred). Not an item expecting near-term action — revisit only if
professional/slot/order/notification counts grow large enough at real v1.0 data volumes to
push a list endpoint's response into the multiple-hundreds-of-KB range.

### 5.6 Actuator exposure — confirmed minimal, no action needed

Checked as part of this pass, recorded for completeness rather than left silently assumed:
`management.endpoints.web.exposure.include: health` (only `health`, `application.yml`) and
`SecurityConfig` permits only `/actuator/health`, not `/actuator/**` — no broader actuator
surface (env, beans, mappings, etc.) is exposed. `show-details: when-authorized` with no
actuator-specific authorization manager configured means health details are not shown to
unauthenticated callers by default — confirmed safe, no gap found here.

---

## 6. Summary — decisions needed before `pronto-coding` acts on any finding above

Consolidated list, for `pronto-lead`/the user, of every item in this document that requires
a decision rather than a straightforward test-and-fix:

1. **§4.1 — the `EXPIRED`-issue-cannot-be-rebooked gap. DECIDED, not open.** The user has
   ruled option 3 (accept the new-issue workaround as intended) as the final, permanent
   design — no reopen endpoint, no relaxed booking guard, ever. No further sign-off needed.
2. **§2.4 — PRD §5.2.4's "personal data management" scope. DECIDED, not open.** Soft-delete
   + anonymization (already built, already QA-verified) is the accepted MVP answer; a
   self-service data-export endpoint is reclassified as deferred backlog for a possible
   future version, not an open question.
3. **§2.5 — AWS root-account credentials in the local dev run config.** Not an open
   decision on *whether* to rotate — this document's language has been strengthened to
   required-before-any-deployment (§2.5). Timing of the actual rotation remains the user's
   own future action; no infrastructure/credential change was made by this documentation
   pass.
4. **§5.1 — checked-in insecure `JWT_SECRET` default.** Recommend a startup fail-fast guard
   as a concrete `pronto-coding` fix this milestone; needs sign-off on priority.
5. **§5.2 — no IP-based rate limiting on auth endpoints.** Recommend as a candidate fix this
   milestone if time allows; needs a priority call, not assumed.
6. **§4.2/§4.3 — email retry/backoff and multi-instance dispatch claim. CONFIRMED DEFERRED,
   not open.** Both re-verified as not load-bearing for any currently-existing behavior
   (confirmed by reading `EmailDispatchJob`/`OrderExpirySweepJob` directly, and by this
   project never having run more than one backend instance) — both are backlog items for
   when a real email provider / real multi-instance deployment is actually planned. No
   further sign-off needed.
7. **§4.4 — slot edit/delete. RESOLVED, reversed and built.** The Milestone 6 "leave alone"
   call was overruled by an explicit user product decision during this same Milestone 7
   pass — `PUT`/`DELETE /api/availability/slots/{slotId}` are now designed, implemented, and
   QA-passed (full regression + targeted tests, zero gaps, including all four order
   lifecycle states for the booking-protection guard). No further sign-off needed; not an
   open item.
8. **§5.5 — pagination. CONFIRMED STILL DEFERRED, not open.** The load test's real measured
   payload size (~1565 bytes for ~7 professionals) is comfortably within this section's own
   "tens of KB, defer" threshold. Re-confirmed during Milestone 7's closing pass with no
   accidental partial implementation found. No further sign-off needed unless real v1.0 data
   volumes later push a list endpoint's payload into the multiple-hundreds-of-KB range.
