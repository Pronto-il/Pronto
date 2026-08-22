# MS0 Report

Production Baseline Audit & Product Freeze.

## Branch

`production/ms0-baseline-audit`

## Base Commit

- **Base commit (Roadmap Baseline):** `18585b484ea838244b3816cc333b159537f6baaf` — "Record the three governing roadmap decisions"
- **Product / SOS Baseline:** `08d91a387a8287060c857ba44008fc4f759f4076` (`08d91a3`) — "Finish Pronto SOS: immediate selection, customer-driven search expansion, final scan"

Product code is byte-identical between the two: the roadmap bootstrap commits (`c3f7037`,
`18585b4`) changed documentation and agent files only. Every finding below therefore applies
equally to the product baseline `08d91a3`.

Migrations present at baseline: `V1` … `V39`.

## Final Status

**PARTIAL**

Two independent reasons, either of which is sufficient:

1. **Execution defect in MS0 itself.** MS0 did not perform a Playbook-mandated audit action —
   "inspect GitHub/CI state" (MS0 Execution Mode, allowed actions; Recorded Baseline finding #2
   explicitly asked MS0 to assess branch protection and the latest `main` run). Instead it recorded
   a **false impossibility claim** — that these were "not inspectable from a local clone" — which
   suppressed a real High finding. The repository is public and both facts were one unauthenticated
   GitHub API call away. The claim has been removed and the measured result recorded (Validations
   19–20, High finding "`main` is unprotected"). A milestone that substituted an assertion for an
   available measurement cannot be DONE.
2. **The audited baseline is not production-deployable**: 13 Critical blockers were found, and MS0
   is product-read-only by rule (Playbook, "MS0 Execution Mode — product-read-only"), so none could
   be closed inside this milestone. Playbook §10 requires PARTIAL when critical Definition-of-Done
   items — here "no production mock silently active" and "no known critical bug" — are not satisfied
   by the audited state.

## Objective

Create a verified production-readiness baseline after the final SOS implementation: identify
exactly what works, what is partial, what is mocked, and what blocks production; run all practical
automated validation; produce a readiness matrix; rank production blockers; and decide whether MS1
can start.

Per the settled MS0 execution mode, MS0 is `AUDIT + VALIDATION + DOCUMENTATION ONLY`. Every defect
below is **documented and routed** to a future milestone or to a separately proposed hotfix — none
is described as fixed, because none was fixed.

## Existing Implementation Found

### Flow map — Customer, Standard booking

| Step | Surface | Backend | Reality found |
| --- | --- | --- | --- |
| 1. Register | Registration form | `POST /api/auth/register` (`AuthController.java:45`, multipart) | 201. Verification code is written to the application log, never emailed (`LoggingEmailSender.java:20`). |
| 2. Verify | Code entry | `POST /api/auth/verify` (`AuthController.java:54`) | 200. Code is plaintext in the DB, 15-minute lifetime, **no resend endpoint exists**. |
| 3. Log in | Login form | `POST /api/auth/login` (`AuthController.java:59`) | 200 with a JWT immediately. **Single-step — no OTP stage exists** (confirms the MS2 premise). |
| 4. Describe issue | Issue form + photos | `POST /api/issues/classify`, `POST /api/issues` (`IssuesController.java:45,51`) | Classification is served by `MockAiClassificationClient` unless an OpenAI key is present; the mock is the `matchIfMissing=true` default. |
| 5. Choose professional | Marketplace list | `GET /api/bookings/professionals?issueId=` (`BookingsController.java:59`) | Returns **all** professionals in the category, `PENDING` included (proven live, see Validation 10). Distance/ETA shown are constants. |
| 6. Choose a time | Slot picker | `GET /api/bookings/professionals/{id}/available-windows` (`BookingsController.java:73`) | Empty for every newly registered professional — registration never seeds `professional_working_hours`. |
| 7. Create order | Confirm | `POST /api/bookings/orders` (`BookingsController.java:83`) | Order created `PENDING`; customer phone and full address are released to the professional at `PENDING`. |
| 8. Track | Order screen (short-polling) | `GET /api/bookings/orders/me`, `/orders/{id}` (`BookingsController.java:125,131`) | `OrderStatus`: `PENDING → CONFIRMED → ON_THE_WAY → COMPLETED` (+ `CANCELLED`, `REJECTED`, `EXPIRED`). |
| 9. Review | Review form | `POST /api/reviews` (`ReviewsController.java:45`) | Ownership + idempotency enforced. Reviewability is gated on `COMPLETED`, which the professional can set unilaterally. |

### Flow map — Professional

Registration and verification use the same `/api/auth` endpoints. After login: profile and
sub-services (`ProfessionalsController.java:44,50,57,66,73`), availability and SOS availability
(`AvailabilityController.java:65-151`), then incoming Standard requests answered through
`POST /api/bookings/orders/{orderId}/accept|reject|on-the-way|complete`
(`BookingsController.java:90-118`). `approval_status` is displayed nowhere and gates nothing on
this path.

### Flow map — SOS

`POST /api/sos/requests` (`SosCustomerController.java:53`) dispatches offers to a candidate pool;
professionals see them at `GET /api/sos/offers` and respond with
`accept` / `reject` / `eta` (`SosProfessionalController.java:43-75`); the customer selects with
`POST /api/sos/requests/{id}/select` and may widen the search with
`POST /api/sos/requests/{id}/scan-again` (`SosCustomerController.java:97,105`); the selected
professional then runs `confirm → on-the-way → arrived → complete`
(`SosProfessionalController.java:86-107`). `SosRequestStatus`:
`CREATED → MATCHING → WAITING_FOR_PROFESSIONALS → WAITING_FOR_CUSTOMER_SELECTION →
PROFESSIONAL_SELECTED → CONFIRMED → ON_THE_WAY → ARRIVED → COMPLETED`
(+ `CANCELLED`, `EXPIRED`, `FAILED`). `SosOfferStatus`:
`OFFERED, VIEWED, ACCEPTED, REJECTED, EXPIRED, SELECTED, NOT_SELECTED`.

Realtime for SOS is STOMP-over-WebSocket at `/ws` (JWT on `CONNECT`, one allow-listed subscribe
destination, client `SEND` refused, delivery-only). Notifications, order status and incoming
requests use short-polling.

### Production readiness matrix

| Area | Status | Evidence |
| --- | --- | --- |
| SOS state machine + concurrency guards | DONE | 235 SOS tests in the passing 547-test suite |
| SOS dispatch / expansion / CAS bound | DONE | Validation 11 (live, 8 → 16 → `409 SOS_EXPANSION_LIMIT_REACHED`) |
| STOMP auth, SUBSCRIBE allow-list, SEND refusal, per-user routing | DONE | `com.pronto.realtime`; unit suite |
| SOS retry model | DONE | unit suite |
| Money computation and snapshotting | DONE | unit suite (no money moves — see External Services) |
| Standard booking authorization / ownership | DONE | no horizontal escalation found on any id-bearing endpoint |
| Double-booking prevention (Standard) | DONE | code pre-check + `ck_orders_no_overlap` DB exclusion constraint |
| Reviews / favorites ownership + idempotency | DONE | unit suite |
| Hebrew / RTL frontend | DONE | `npm run build` (Validation 5) |
| Backend unit suite | DONE | Validation 1 |
| Frontend lint / typecheck / build | DONE | Validations 4, 5 |
| Flyway clean migration V1→V39 | DONE | Validation 6 (first time ever verified) |
| Approval lifecycle | PARTIAL | C1 |
| Email verification flow | PARTIAL | C3, C4, C5 |
| Login | PARTIAL | single-step; account-existence and timing oracles |
| Rate limiting | PARTIAL | `getRemoteAddr()` keying; unbounded map; per-instance |
| Professional brief grounding | PARTIAL | C13 |
| Deadline enforcement (SOS confirmation grace) | PARTIAL | sweep-only, no lazy fallback |
| Approval enforcement in Standard | **BROKEN** | C1, Validation 10 |
| Verification-document review | **BROKEN** | C2 |
| Expired-code recovery | **BROKEN** | C5, Validation 9 |
| SOS email content | **BROKEN** | Validation 13 (`Order #null`, English) |
| Email delivery status | **BROKEN** | C12 |
| `pronto.email.mode` | **BROKEN** | C3 — read by zero Java code |
| Email delivery | MOCK | `LoggingEmailSender` |
| AI classification | MOCK | `MockAiClassificationClient`, `matchIfMissing=true` |
| Distance / ETA | MOCK | `ApproximateDistanceEtaStrategy` constants |
| Local disk storage mode | MOCK | `LocalDiskStorageClient`, `matchIfMissing=true` |
| Real OpenAI path | NOT VERIFIED | needs paid key |
| Real S3 path | NOT VERIFIED | never exercised |
| Multi-instance behavior | NOT VERIFIED | C10 |
| CI workflows green on baseline `08d91a3` | DONE | Validation 20 — `backend-ci` + `Frontend CI` both `completed/success` |
| CI **enforcement** on `main` | **BROKEN** | Validation 19 — `protected: false`, required status checks `off`; CI is advisory only (High, MS5) |
| DB constraints + guarded UPDATEs under real concurrency | NOT VERIFIED | C11 |
| Browser-level E2E | NOT VERIFIED | no harness exists |

## Changes Made

**None to product code, configuration, migrations, or tests.** MS0 is product-read-only.

The only artifacts produced are this report and the roadmap tracker:

- `docs/production-roadmap/README.md`
- `docs/production-roadmap/reports/MS0-report.md`

**No hotfix was applied.** Recommendation: apply none outside a milestone. The two most tempting
candidates — C4 (verification codes in logs) and C6 (unguarded HMAC secret) — are only exploitable
in a deployed environment, and no deployed environment exists yet. Both belong to MS5, which
creates one.

## Files Changed

| File | Change |
| --- | --- |
| `docs/production-roadmap/README.md` | Added — milestone tracker |
| `docs/production-roadmap/reports/MS0-report.md` | Added — this report |

No file under `backend/src`, `frontend/src`, `backend/src/main/resources/db/migration`, or
`.github/` was modified.

## Database Changes

**None.** No migration was added, edited or removed; `V1` … `V39` are unchanged.

For validation only, a **temporary scratch database** `pronto_ms0_audit` was created on the local
docker `pronto-postgres` container, migrated from zero, and **dropped** at the end of the
milestone. The developer's local `pronto` database was not touched — 23 tables before, 23 tables
after. All QA fixtures created for validation were removed.

## API Changes

**None.** No endpoint, request shape, response shape, status code or error code was added, removed
or altered.

## Frontend Changes

**None.** No frontend source file was modified. Frontend findings are recorded below and routed to
future milestones.

## Configuration / Environment Variables

No configuration was changed. The audit of production-sensitive configuration found:

| Setting | Default | Finding | Route |
| --- | --- | --- | --- |
| `pronto.environment` | `local` | The only environment switch; Spring profiles are unused. It triggers `JwtSecretStartupGuard` but is itself unguarded. | MS5 |
| `JWT_SECRET` | — | Guarded by `JwtSecretStartupGuard` (one of only 2 guards in the repo). | — |
| storage client selection | `matchIfMissing=true` → `LocalDiskStorageClient` | Silently ephemeral local disk in production (C7). | MS5 |
| `STORAGE_PUBLIC_BASE_URL` | `http://localhost:8080` | Baked into image URLs served to real customers (C7). | MS5 |
| HMAC URL-signing secret | committed placeholder | `LocalHmacUrlSigner` accepts signatures forged from the committed secret (C6). | MS5 |
| AI client selection | `matchIfMissing=true` → `MockAiClassificationClient` | Silently mocked in production (C8). | MS5 / MS4 |
| `pronto.email.mode` (`EMAIL_MODE`) | — | **Read by zero Java code.** Setting `smtp` changes nothing and errors nothing (C3). | MS2 / MS5 |
| `SOS_CANDIDATE_POOL_SIZE` / `SOS_EXPANSION_POOL_INCREMENT` | `8` / `8` | Guarded by `SosProperties`; now validated live (Validation 11). | — |
| CORS allowed origin | `http://localhost:5173` | Still emitted in production mode (Validation 16). | MS5 |

Only **2 startup guards** exist (`JwtSecretStartupGuard`, `SosProperties`); 6 of the 7
production-sensitive settings boot silently.

**No real secret is committed** — `git grep` for `sk-…`, `AKIA…`, private-key headers, `ghp_` and
`xox…` over tracked files returned no matches (Validation 17). The committed HMAC value is a
placeholder, which is precisely why C6 is exploitable: the placeholder is *usable*.

## Security Review

### Critical blockers (ranked)

| # | Finding | Evidence | Route |
| --- | --- | --- | --- |
| **C1** | **Professional approval is decorative.** `professionals.approval_status` DEFAULT `'APPROVED'` (`V4__create_professionals.sql:9`); `Professional.java:88-94` hardcodes `"APPROVED"`; the entity has **no setter** and no code path anywhere can change the value. `SosCandidateRepository.java:72` is the only query in the backend that filters it — Standard listing (`ProfessionalListingRepository.java:46-55`), available-windows, order creation (`BookingsService.isProfessionalActive:571-575`), public profile, favorites and reviews do not. **This is a backend defect end to end.** The green "בעל מקצוע מאומת" trust badge is *correctly* conditional in the frontend — `ProfessionalProfileDisplay.tsx:61` and `SosProfessionalSheet.tsx:220` both render only when `approvalStatus === 'APPROVED'` — but every professional satisfies that condition because the backend can never produce any other value. The badge therefore appears for 100% of professionals at the moment a customer chooses whom to let into their home. **MS1 must not "fix" this frontend code: it needs no change.** Once the backend can emit `PENDING`/`REJECTED`, the existing conditions become correct automatically. | Validations 10, 12 (live) | **MS1** |
| **C2** | **Verification documents are write-only.** Uploaded to `verification-documents/{userId}/{uuid}` (`AuthService.java:131-138`), stored in `professionals.verification_document_key` (`V21`), and read back by **nothing** — no DTO exposes the key, no endpoint returns it, no operator surface exists, and no admin role exists (`ck_users_role` permits only `CUSTOMER`/`PROFESSIONAL`). Not even the owner can retrieve it. Verification is theatre. | code | **MS1** |
| **C3** | **No email delivery exists, and it cannot be switched on.** `LoggingEmailSender` is an unconditional `@Component` and the only `implements EmailSender` in the repository. `pronto.email.mode` is read by zero Java code (verified exhaustively). | code | **MS2** (provider) / **MS5** (guard) |
| **C4** | **Verification codes logged in clear text at INFO**, next to the recipient address, on every registration (`LoggingEmailSender.java:20`). Log read access = account takeover for every unverified account. Violates Playbook §5. | Validations 7, 14 (live) | **MS2 / MS5** |
| **C5** | **Expired verification code = permanent, unrecoverable account loss.** No resend endpoint (`AuthController` exposes only `/register`, `/verify`, `/login`), re-registration blocked by `409 DUPLICATE_EMAIL`, login blocked by `403 EMAIL_NOT_VERIFIED`, no password reset anywhere, no operator tool. The UI tells the user to re-register (`VerifyCodeForm.tsx:15`) — advice that cannot work. | Validation 9 (live) | **MS2** |
| **C6** | **Committed HMAC secret + no startup guard ⇒ unauthenticated signature forgery** on `GET /api/storage/images/**`, which is `permitAll` and where the HMAC *is* the entire authentication. Any object key — private customer photos or verification documents — is downloadable by an anonymous caller who learns the key, and object keys **are logged** (`IssueImageResolver.java:55,75`), completing the chain. | Validation 15 (live) | **MS5** |
| **C7** | **Storage silently defaults to ephemeral local disk in production** (`matchIfMissing=true`), and `STORAGE_PUBLIC_BASE_URL` then bakes `http://localhost:8080` into image URLs served to real customers. | Validation 14 (live) | **MS5** |
| **C8** | **AI silently defaults to a Hebrew keyword mock in production** (`matchIfMissing=true`), emitting literal `[מוק]` strings to customers and routing by substring match. | Validation 14 (live) | **MS5** (guard) / **MS4** |
| **C9** | **Placeholder distance/ETA presented to customers as precise measured data.** Two constants (8.0 km same-city, 35.0 km otherwise) rendered as `{distanceKm.toFixed(1)} ק״מ ממך` and `כ־{etaMinutes} דקות` (`ProfessionalCard.tsx:154,158`, `SosCandidateCard.tsx:86`, `SosOfferCard.tsx:146`), persisted to `orders.expected_arrival_at`, and driving 50% of the SOS ranking weight plus the eligibility radius. Every same-city professional reads exactly "8.0 ק״מ". | code | **MS3** |
| **C10** | **Realtime is single-instance by construction.** In-memory STOMP broker; any deployment with ≥2 instances silently loses realtime for a fraction of users. Degrades to polling rather than breaking, but the deployment target is a managed container service. | code | **MS5** |
| **C11** | **Zero integration / DB / E2E coverage.** No `@SpringBootTest`, `@DataJpaTest`, Testcontainers or `@Sql` anywhere in 45+ backend test files; the frontend has no test runner at all (no vitest/jest/playwright in `package.json`). CI provisions a `postgres:16` service that is never used. Every DB constraint, every guarded `@Modifying` UPDATE, every migration and JPA `validate` is unexercised by automation. | Validation 3 (0 DB-connection log lines across the whole suite) | **MS5** owns per **D3**; **MS8** consumes |
| **C12** | **`EmailDispatchJob` writes `SENT` + `sentAt` for mail that was never sent** (`EmailDispatchJob.java:65-68`) — false delivery status persisted as a database fact. | code | **MS2** |
| **C13** | **AI routing accuracy is NOT MEASURED.** The real-model evaluation requires a paid key and has never been run — it is the 1 skipped test (`OpenAiClassificationEvaluationRunnerTest`). The offline harness ran clean on 24 labelled cases but measures the mock, not OpenAI. No evidence exists for the 95% target. The dataset is also too small: `painting` n=1, `locksmith` n=2, no holdout split, no image cases. | Validations 1, 2 | **MS4** |

#### MS3 scope input — no routing origin exists for a professional

C9 is only half of MS3's problem. The other half is that **there is nothing to route *from***: a
professional's only location datum is the free-text `service_area VARCHAR(150)`
(`V4__create_professionals.sql:8`), supplemented by a nullable, self-typed `city VARCHAR(100)`
(`V15`, backfilled with `UPDATE professionals SET city = service_area WHERE city IS NULL` — i.e.
from the same free text). `matching` consumes no coordinates at all. Real geocoding, distance and
travel duration cannot be computed until a geocodable professional origin exists, so establishing
one is an input to MS3, not an optional extra. Note also the standing constraint that a
professional's exact private address must never be exposed to customers merely because routing
uses it.

### High findings

19 findings.

| Finding | Route |
| --- | --- |
| **`main` is unprotected and CI is advisory only.** `GET /repos/yuvalharel/Pronto/branches/main` returns `"protected": false` with `required_status_checks.enforcement_level: "off"` and empty `contexts`/`checks`. Anyone can push directly to `main` and merge red. Both workflows exist, are wired, and pass — nothing enforces them. This closes the Playbook Recorded Baseline finding #2, which asked MS0 to assess exactly this | MS5 |
| Rate limiter keyed on `getRemoteAddr()` — behind a load balancer it collapses all clients into one bucket, simultaneously failing to throttle attackers and locking out legitimate users; unbounded map; per-instance counters | MS5 |
| Account-existence oracles on `/login` (423 vs 401) and `/verify` (409 vs 400); login timing oracle (unknown user skips BCrypt) | MS2 |
| Verification codes stored in plaintext in the DB; no per-user / per-code attempt cap | MS2 |
| No password reset flow exists anywhere | MS2 |
| SOS confirmation grace (180s) is **sweep-only** — no lazy fallback. If the scheduler stops, a request whose selected professional never confirms stays `PROFESSIONAL_SELECTED` forever, holding the order and the issue, with the customer on a waiting screen | MS8 |
| SOS transactional emails render `Pronto — Order #null` in English for every event, both sides | MS2 |
| SOS orders have `booked_end = NULL` and are structurally exempt from `ck_orders_no_overlap` — a professional on an SOS job still shows as available and can be double-booked | MS8 |
| New professionals are listed but unbookable: registration never seeds `professional_working_hours`, so available-windows is empty; the customer discovers the dead end at step 3 of 4 | MS1 |
| `isPubliclyReadable` is a raw `startsWith("professionals/")` with no canonicalization — the ownership gate is bypassable by construction (`..`), currently blocked only downstream by Spring's `StrictHttpFirewall` | MS8 |
| No verification-document lifecycle: never deleted on account deletion; `deleteMe` leaves phone, address and password hash intact | MS1 / MS8 |
| No rate limit on `POST /api/issues/classify` (paid OpenAI endpoint) or on any SOS endpoint | MS4 / MS8 |
| Zero automated tests for login, lockout, verification, rate limiting, startup guards, or `SecurityConfig` route rules | MS8 |
| Post-login deep-link is lost (`RequireAuth` stores `state.from`, `LoginForm` navigates by role) | MS8 |
| Frontend categories are hardcoded (`shared/api/categories.ts:21-31`) despite `GET /api/categories` existing and being called elsewhere | MS8 |
| Backend is on Spring Boot 3.3.4, past OSS support; no advisory scan was run | MS5 |
| No TLS/HSTS/CSP/Referrer-Policy config, no `logback-spring.xml` (unstructured logs), no Dockerfile or release-artifact pipeline | MS5 |
| Selected SOS professional gets the exact address but no customer contact channel; the customer sees no phone either | MS7 / MS10 |
| Only 2 startup guards exist (`JwtSecretStartupGuard`, `SosProperties`); 6 of 7 production-sensitive settings boot silently. The JWT guard's own trigger (`pronto.environment`) is itself unguarded | MS5 |

### Medium findings (summary)

Duplicate-email read-then-insert race surfaces as 500 rather than 409; storage writes happen inside
the registration transaction; 24h non-revocable JWT held in `localStorage`; a unilateral `COMPLETED`
lets a professional manufacture a reviewable job; customer phone + full address are released to the
professional at `PENDING`, contradicting the SOS privacy model that `SosAddressAccess` was built to
enforce; a dead `availability_slots` subsystem (live endpoints, no reader) and a genuinely dead
`sub_service_id` column (written as constant `null`); `System.out.println("WORKING DIR = ...")` in
`ProntoApplication.java:18`; no root `.gitignore` and frontend `.env` unignored; N+1 queries on the
hottest polled SOS reads; the notification feed is unpaginated and polled every 4s with no
visibility gating; `pronto.ai.routing.high-confidence` is production-dead (test-only).

**Correction — SOS `latitude` / `longitude` are not dead.** They are accepted on
`CreateSosRequestRequest`, persisted (`SosService.java:180`), privacy-gated behind `SosAddressAccess`
and returned only on exact-address access (`SosResponseAssembler.java:107-108`). The accurate finding
is narrower: **`matching` never consumes them.** They are the existing seam MS3 builds on, not dead
weight to remove — do not drop these columns.

### Clean results

- **No committed secrets.** `git grep` for `sk-…`, `AKIA…`, private keys, `ghp_`, `xox…` over
  tracked files → no matches.
- **No `TODO` / `FIXME` / `HACK` / `XXX`** anywhere in `backend/src`, `frontend/src`, `.github`.
- **No horizontal privilege escalation** found on any id-bearing Standard-booking endpoint.
- **SOS approval filtering works** — the exact inverse of C1 (Validation 12).

## Tests Added / Updated

**None.** MS0 may not modify committed tests. No test file was added, edited or deleted.

## Validation Executed

| # | Command / action | Result |
| --- | --- | --- |
| 1 | `mvn -B verify` (in `backend/`) | **BUILD SUCCESS — Tests run: 547, Failures: 0, Errors: 0, Skipped: 1**, 14.9s. **Deviation:** `mvn -B clean verify` **FAILED** at `maven-clean-plugin` — `Failed to delete backend\target` (IDE file lock); re-run without `clean`. CI uses `clean verify`. |
| 2 | Identify the skipped test | `OpenAiClassificationEvaluationRunnerTest` — the real-model AI evaluation, skipped because it requires a paid `OPENAI_API_KEY`. |
| 3 | `grep -icE "HikariPool\|Flyway\|jdbc:postgresql"` over the full test log | **0.** The 547-test suite never opens a database connection; the whole suite runs in 14.9s. |
| 4 | `npm run lint` (in `frontend/`) | exit 0 — 3 `react(only-export-components)` warnings only. |
| 5 | `npm run build` (in `frontend/`) | exit 0, built in 1.01s. `dist/assets/index-B8o1GDvp.js` 672.20 kB (gzip 202.23 kB), chunk-size warning >500 kB. This runs `tsc -b`, so the typecheck passed. |
| 6 | **[LIVE] Clean Flyway migration + JPA `validate` against real PostgreSQL 16.14** — booted the built jar against a NEW empty scratch database `pronto_ms0_audit` on the docker `pronto-postgres` container | `Successfully applied 39 migrations to schema "public", now at version v39 (execution time 00:00.988s)`; zero ERROR lines; `Tomcat started on port 8080`; `/actuator/health` → `{"status":"UP"}`; 23 tables created; Hibernate `ddl-auto: validate` passed. **This is the first time V1–V39 have been validated from zero against real PostgreSQL — CI has never done it.** The developer's local `pronto` DB was untouched (23 tables before and after). |
| 7 | **[LIVE]** `POST /api/auth/register` | 201. Log line: `c.pronto.auth.email.LoggingEmailSender : [MOCK EMAIL] Verification code for ms0.audit@example.com: 041240 (no real email sent — LoggingEmailSender)`. |
| 8 | **[LIVE]** `POST /api/auth/verify` then `POST /api/auth/login` | 200, then 200 returning a JWT immediately. **Login is single-step; no OTP step exists** — confirms the MS2 premise. |
| 9 | **[LIVE]** Re-register the same email after code expiry | `409 DUPLICATE_EMAIL "Email is already registered."` Combined with no resend endpoint and `403 EMAIL_NOT_VERIFIED` on login, the account is permanently locked out with no self-service or operator recovery. |
| 10 | **[LIVE]** Seeded 20 plumbers (16 `APPROVED` + 4 `PENDING`), all SOS-available; called `GET /api/bookings/professionals?issueId=…` as a customer | Returned **20 professionals — including all 4 PENDING**. `ProfessionalListingRepository.listByCategory` filters only `categoryId` and `users.deleted_at IS NULL`. **Approval enforcement gap proven.** |
| 11 | **[LIVE]** SOS 8/8 defaults against PostgreSQL (the pre-recorded `NOT VERIFIED / HIGH` baseline item) | `POST /api/sos/requests` → 201 with `offerCount: 8` — exactly `SOS_CANDIDATE_POOL_SIZE=8`. `scan-again` #1 → `offerCount: 16, searchExpansions: 1`. `scan-again` #2 → `offerCount: 16, searchExpansions: 2, canExpandSearch: false` (16 = all eligible approved professionals; the +8 increment was applied, supply was the binding constraint). `scan-again` #3 → `409 SOS_EXPANSION_LIMIT_REACHED`. **This baseline finding is now VERIFIED and is downgraded from NOT VERIFIED / HIGH.** |
| 12 | **[LIVE]** Of the 16 offers dispatched, `SELECT approval_status, count(*) … GROUP BY` | `APPROVED \| 16`. Zero offers to `PENDING` professionals. **SOS approval filtering works** — the exact inverse of finding 10. |
| 13 | **[LIVE]** SOS transactional email content | `[MOCK EMAIL] To: ms0.pro01@example.com \| Subject: Pronto — Order #null \| Body: Pronto — Order #null: status changed to SOS_OFFER_RECEIVED`. English copy with a literal `#null`, in a Hebrew-only product, for every SOS event on both sides. |
| 14 | **[LIVE, by QA agent]** `PRONTO_ENVIRONMENT=production JWT_SECRET=<real> java -jar …` | Started successfully **fully mocked**: conditions report shows `LocalDiskStorageClient matched`, `LocalHmacUrlSigner matched`, `MockAiClassificationClient matched`; `OpenAiClassificationClient` and `S3StorageClient` did not match. Registration against it returned 201 and logged the code. |
| 15 | **[LIVE, by QA agent]** HMAC forgery against `GET /api/storage/images/**` | A signature computed offline from the repository's own committed placeholder secret was **ACCEPTED** (502 = past auth, file absent); a garbage signature returned 401. **Unauthenticated signature forgery confirmed.** |
| 16 | **[LIVE, by QA agent]** CORS in production mode | The API still emits `Access-Control-Allow-Origin: http://localhost:5173` and 403s other origins. |
| 17 | `git grep` for `sk-…` / `AKIA…` / private keys / `ghp_` / `xox…` over tracked files | **No matches. No real secret is committed.** |
| 18 | `grep -rn "TODO\|FIXME\|HACK\|XXX"` across `backend/src`, `frontend/src`, `.github` | **Zero hits.** |
| 19 | `GET https://api.github.com/repos/yuvalharel/Pronto` and `GET https://api.github.com/repos/yuvalharel/Pronto/branches/main` (unauthenticated) | Repository is public (`"private": false`). `main` is **`"protected": false`**, with `required_status_checks` = `{"enforcement_level": "off", "contexts": [], "checks": []}`. **CI is advisory only — nothing prevents a direct push to `main` or a red merge.** New High finding, routed to MS5. |
| 20 | `GET https://api.github.com/repos/yuvalharel/Pronto/actions/runs?branch=main` (unauthenticated) | `total_count: 31`. For the Product/SOS Baseline `08d91a3`: `backend-ci` → `completed/success` and `Frontend CI` → `completed/success`, both at `2026-08-22T07:15:01Z`. **The baseline is green on both workflows.** |

## Manual QA Performed

All `[LIVE]` rows above are manual QA against a running application backed by real PostgreSQL
16.14: registration → verification-code capture from logs → verify → login (single-step JWT);
expired-code lockout probing across `/register`, `/verify` and `/login`; a 20-professional seeded
Standard listing check that exposed the approval gap; a full SOS request → 8 offers → two
`scan-again` expansions → expansion-limit rejection cycle with a per-offer `approval_status`
aggregate; SOS transactional-email content inspection; a production-mode boot with the Spring
conditions report; an offline HMAC signature-forgery attempt; and a production-mode CORS probe.

All QA fixtures and the scratch database were removed afterwards.

## Known Limitations

Baseline limitations carried in, and their MS0 disposition:

1. **SOS production-default validation** (`NOT VERIFIED / HIGH` at baseline) — **now VERIFIED and
   downgraded** by Validation 11.
2. **CI enforcement — now MEASURED, and this baseline finding is closed.** An earlier draft of this
   report claimed branch protection and the latest `main` run were "not inspectable from a local
   clone." **That was false** — the repository is public and both were one unauthenticated GitHub
   API call away. Measured (Validations 19–20): `main` is `"protected": false` with
   `required_status_checks.enforcement_level: "off"`, so **CI is advisory only** (new High finding,
   MS5); and the Product/SOS Baseline `08d91a3` is **green on both `backend-ci` and `Frontend CI`**.
   The previously stated residual risk — "a red or bypassed `main` could already have merged" —
   is therefore **resolved: it did not happen.**
3. **Backend CI does not exercise persistence** — confirmed by Validation 3 (zero DB-connection log
   lines). This is C11 and is owned by MS5 per **D3**.

Fake / mock behavior active at baseline (the "no silent production mock" list):

| Mock | Selected by | Consequence |
| --- | --- | --- |
| `LoggingEmailSender` | unconditional `@Component`; sole `EmailSender` | No email is ever delivered; codes are logged (C3, C4) |
| `MockAiClassificationClient` | `matchIfMissing=true` | Hebrew keyword matching, literal `[מוק]` strings to customers (C8) |
| `LocalDiskStorageClient` | `matchIfMissing=true` | Ephemeral disk + `http://localhost:8080` URLs (C7) |
| `LocalHmacUrlSigner` | `matchIfMissing=true` | Committed placeholder secret ⇒ forgeable signatures (C6) |
| `ApproximateDistanceEtaStrategy` | the only `DistanceEtaStrategy` | 8.0 km / 35.0 km constants shown as measured data (C9) |

All five are active by default in `PRONTO_ENVIRONMENT=production` (Validation 14). This alone makes
the baseline non-deployable.

**Documentation-sync note (non-blocking).** `.claude/pronto-production-rules.md:305` states that
`JwtSecretStartupGuard` is "the only startup guard." That is stale: `SosProperties.java:195-197`
refuses to start on an invalid `pronto.sos.expansion-radius-multiplier`, making it a second guard —
which is why this report counts **2**. Flagged for correction in a future milestone; MS0 is
product-read-only and did not edit that file.

## External Services / Cost Impact

- **MS0 incurred no external-service cost.** No paid API was called. The OpenAI evaluation test was
  skipped precisely because it requires a paid key (Validation 2).
- **No payment processing exists** anywhere in the repository — no provider, checkout, payment
  status, refund, invoice, payout or settlement. `orders.final_price` and the SOS pricing/commission
  snapshot are computed and displayed, but no money moves. The closed-beta model is direct /
  off-platform payment pending the MS6 decision.
- Cost exposure identified for later milestones: `POST /api/issues/classify` is a paid-OpenAI
  endpoint with **no rate limit** (High finding, MS4/MS8) — a live cost risk the moment the real
  OpenAI client is enabled.

## Rollback / Recovery Notes

- **Nothing to roll back.** No product code, configuration, migration or test was changed; no
  schema change was made to any persistent database.
- The temporary scratch database `pronto_ms0_audit` was dropped; the developer's local `pronto`
  database was verified unchanged (23 tables before and after).
- Discarding this milestone is simply deleting the two documentation files; there is no operational
  or data effect.
- Recovery gaps found in the *product* (not caused by MS0) are C5 (no verification-code recovery
  path of any kind) and the sweep-only SOS confirmation grace with no lazy fallback.

## Not Verified

| Item | Why not run | Residual risk |
| --- | --- | --- |
| Real OpenAI classification path | Requires a paid `OPENAI_API_KEY`; the evaluation is the 1 skipped test | Routing accuracy is unmeasured — no evidence supports the 95% target (C13) |
| Real S3 storage path | No AWS credentials/bucket provisioned; `S3StorageClient` never matched | Object-key layout, permissions and URL signing are unexercised against a real bucket |
| Multi-instance behavior | Only single-instance runs were possible locally | In-memory STOMP broker and per-instance rate-limit counters both break silently at ≥2 instances (C10) |
| Every DB constraint and guarded `@Modifying` UPDATE under real concurrency | No integration/DB test harness exists (C11) | Constraint and CAS behavior is asserted only by unit tests with mocks |
| All browser-level E2E | No E2E harness exists in the repository | No end-to-end regression signal for either flow |
| Dependency advisory scan (Spring Boot 3.3.4, past OSS support) | No scanner configured | Unknown known-CVE exposure |

## Production Risks Remaining

All 13 Critical and all 19 High findings above remain **open and unfixed** — MS0 fixed
nothing. Restated by owning milestone:

| Milestone | Open Critical items it must close |
| --- | --- |
| MS1 | C1 (approval unenforced in Standard), C2 (verification documents unreadable) |
| MS2 | C3 (no email provider), C4 (codes in logs), C5 (unrecoverable lockout), C12 (false `SENT` status) |
| MS3 | C9 (placeholder distance/ETA shown as real), plus the High routing-origin gap — there is no geocodable professional origin to route from |
| MS4 | C13 (AI accuracy unmeasured), C8 (mock AI content) |
| MS5 | C6 (HMAC forgery), C7 (ephemeral storage default), C8 (mock guard), C10 (single-instance realtime), C11 (integration/E2E infrastructure, per D3), plus the High finding that `main` is unprotected and CI is advisory only |
| MS8 | consumes MS5's infrastructure for C11 |

**Top-line risk:** the baseline boots to a fully mocked production (Validation 14) with an
anonymously forgeable image-access signature (Validation 15), zero enforced professional approval
on the Standard path (Validation 10), and no way to recover an account whose verification code
lapsed (Validation 9).

Two items need Lead / user adjudication rather than a silent default:

1. Whether the SOS confirmation-grace **lazy fallback** warrants a separately-approved hotfix
   rather than waiting for MS8.
2. Whether SOS **commission should be displayed to professionals at all** under MS6 Decision A.

## Final Working Tree Status

**Clean.** The scratch database was dropped, QA fixtures were removed, and all temporary files were
**removed or gitignored** — `backend/qa-tmp/` still exists on disk but is gitignored
(`backend/.gitignore:43`) and predates MS0, which satisfies the rule as written ("removed **or**
ignored"). The only untracked/new content is the two documentation files listed under *Files Changed*,
which are the MS0 deliverable and are handed to Lead **uncommitted**, per the milestone lifecycle
(Playbook §1).

## Recommended Commit Message

> Recommendation only. MS0 does not commit, push, merge, or open a PR. This runs only after
> explicit user approval.

```text
Record the MS0 production baseline audit

Add the production roadmap tracker and the MS0 baseline audit report for
production/ms0-baseline-audit, based on 18585b4 (product baseline 08d91a3).

MS0 is product-read-only: no application source, configuration, migration
or test was changed. The report records the verified flow map, the
production readiness matrix, 13 Critical and 19 High findings each routed
to an owning milestone, the active mock inventory, and the automated and
live validation results — including the first clean Flyway V1-V39
migration and JPA validate run against real PostgreSQL 16.14, live
verification of the SOS 8/8 pool defaults, and the measured GitHub CI
state (main unprotected and required status checks off, baseline 08d91a3
green on both workflows).

Final status: PARTIAL — the baseline is not production-deployable, no
finding was fixed, and MS0 initially skipped the mandated GitHub/CI state
inspection and recorded a false impossibility claim in its place, which
is now corrected.
```

## Recommended Next Step

**MS1 can start.** C1 and C2 are precisely MS1's scope, and no MS0 finding blocks MS1's work.

Two scope inputs discovered in MS0 that MS1 must absorb:

1. **There is no admin/operator role**, and `ck_users_role` forbids adding one without a migration.
   MS1's "minimal operator capability" is therefore a **from-zero build**: the role itself, the
   authorization path, and a secure verification-document read path (C2).
2. **Keep the existing `PENDING` state** per governing decision **D1** — do not rename to
   `PENDING_REVIEW`.

MS1 should also pick up the High finding that new professionals are listed but unbookable because
registration never seeds `professional_working_hours`, and the missing verification-document
lifecycle on account deletion.

Before MS1 begins, the milestone lifecycle requires explicit user approval of this milestone,
followed by commit → push → merge → sync of `production/ms0-baseline-audit` into `main`. Only then
may `production/ms1-professional-verification` be created.

No hotfix is recommended outside a milestone.
