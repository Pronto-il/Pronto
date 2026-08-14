# Pronto — Implementation Plan

Ordered milestones derived from `docs/architecture/overview.md` §5, with per-milestone
responsibilities across the five project agents (`pronto-lead`, `pronto-planning`,
`pronto-coding`, `pronto-qa`, `pronto-documentation`). No milestone is "done" without a
QA pass and up-to-date package docs. No milestone includes a git push/merge/PR — that
requires separate, explicit user approval regardless of milestone completion.

Every milestone follows the same cycle: **Planning** (spec, if not already covered by
`overview.md`) → **Coding** (implementation) → **QA** (validation against the milestone's
acceptance criteria) → **Documentation** (package `.md` files + any doc updates) →
**Lead** (reviews all of the above for consistency before the milestone is considered
closed).

## Milestone 0 — Foundation

- **Goal**: a running skeleton both devs can build on.
- **Scope**: Spring Boot project init (`backend/`) with the package structure from
  `overview.md` §4 as empty stub packages + stub `.md` docs; React project init
  (`frontend/`) with the `src/features/*` + `src/shared/*` structure, also stubbed;
  Postgres via docker-compose for local dev; DB migration tooling (Flyway or Liquibase)
  with the base schema from PRD §6 (Users, Professionals, AvailabilitySlots, Issues,
  IssueImages, Orders, Notifications) translated to Postgres DDL; basic CI (build + lint)
  if in scope for the team's time budget.
- **Acceptance criteria**: backend builds and runs with a health endpoint; frontend builds
  and runs with a placeholder home route; migrations apply cleanly against a fresh
  Postgres instance; every stub package has its `.md` file.
- **Removes**: the leftover `src/main/java/main.java` "Hello World" and
  `out/production/pronto/main.class` scaffolding once the real Spring Boot project
  supersedes them (confirm with user before deleting).

## Milestone 1 — Auth & user management

- **Status: COMPLETE (backend), 2026-08-13.** `auth`, `users`, `professionals` packages
  implemented per `docs/architecture/api-contract.md`; QA validated in two passes (full
  validation, then a re-verification pass after a login-lockout bug fix) — see "QA
  summary" below. **Frontend `features/auth` screens are intentionally deferred**, not an
  oversight — the user has not yet finalized the frontend design system, and building auth
  screens ahead of that decision was explicitly deprioritized. Do not read the absence of
  frontend auth work as a gap in this milestone's backend completion.
- **Scope**: `auth`, `users`, `professionals` (profile only — no approval workflow, v1.0
  auto-approves professional accounts). Registration, email verification code, login,
  password hashing, account lockout after 5 failed attempts, account deletion endpoint.
- **Acceptance criteria**: a customer and a professional can both register, verify, and
  log in; failed-login lockout is enforced; a verified professional can receive bookings
  immediately, with no separate approval step. **All met**, backend-only.
- **QA summary**: live-validated against a real Postgres instance — registration/
  verification/login for both roles, professional auto-approval (no admin gate), lockout
  enforcement (5 attempts → 15-min lock → auto-expiry/reset), soft-delete + PII
  anonymization on account deletion, JWT auth enforcement on `/api/users/me`,
  `/actuator/health` still public (no Milestone 0 regression), and edge cases
  (case-insensitive duplicate email, expired/consumed verification codes, invalid
  `categoryId`). First pass caught a critical lockout-bookkeeping bug (fixed via
  `LoginAttemptRecorder`, see `backend/src/main/java/com/pronto/auth/README.md`
  "Transaction boundaries"); second pass re-verified the fix and passed fully.
- **Known gaps, not blockers**: no password-reset flow, no resend-verification-code
  endpoint, no refresh-token/logout-revocation mechanism — see `overview.md` §6 and
  `api-contract.md` §4 for detail.

## Milestone 2 — Issue creation & AI classification

- **Status: COMPLETE (backend), 2026-08-13.** `issues`, `ai`, `storage` packages
  implemented per `docs/architecture/api-contract-issues.md`; QA validated live against a
  real Postgres instance, with one bug found and fixed mid-milestone (a role-check-vs-
  validation ordering bug) — see "QA summary" below. **Frontend `features/issues` screens
  (Home/New Issue, AI Review) are intentionally deferred**, consistent with the rest of
  `frontend/` — not a gap in this milestone's backend completion.
- **Scope**: `issues`, `ai`, `storage` (image upload). Home/New Issue screen, AI
  Review screen with confirm/edit, image upload with the 5s target.
- **Acceptance criteria**: a customer can describe an issue, optionally attach images,
  receive an AI-suggested category, and confirm or override it before proceeding. **Met**,
  backend-only — verified end-to-end (upload → classify → confirm/override → create) against
  a real Postgres instance with the default mock AI classifier and local-disk storage.
- **QA summary**: live-validated against a real Postgres instance — the full happy path
  (upload image → classify → confirm/override category → create issue), the
  ephemeral-classify-vs-persisted-create distinction (held up under ~25 requests to
  `/classify` with zero premature persistence, confirming §2.1's "no DB write" guarantee),
  image ownership enforcement (a forged/mismatched-owner `imageKey` correctly `400`s with
  `IMAGE_KEY_INVALID`; cross-customer retrieval via `GET /api/storage/images/**` correctly
  `403`s), validation/error-code coverage across both packages, mock AI classifier sanity
  (Hebrew keyword-based category matches, `general_handyman` fallback with `confidence:
  null` on no match), local storage round-trip byte-identity (upload → retrieve produces an
  identical byte array), and no regression to Milestone 1 (`/actuator/health`,
  `/api/auth/*`, `/api/users/me` all still work). **One real bug found**: role-check-vs-
  validation ordering meant a professional-role token combined with a malformed request body
  returned `400 VALIDATION_ERROR` instead of the contract-mandated `403 FORBIDDEN` on 3
  endpoints (`POST /api/issues/classify`, `POST /api/issues`, `POST /api/storage/images`) —
  `RoleGuard.requireRole` was being called from inside each controller method body, which
  runs *after* Spring resolves `@Valid`/`@RequestParam` argument binding for the matched
  handler. Fixed by introducing `common.security.RoleRequiredInterceptor` (a
  `HandlerInterceptor` that calls `RoleGuard.requireRole` from `preHandle`, which always
  runs before argument resolution), registered per-package via a new `WebMvcConfigurer`
  (`issues.config.IssuesWebConfig`, `storage.config.StorageWebConfig`) rather than touching
  `auth.config.SecurityConfig` — see `backend/src/main/java/com/pronto/common/README.md`
  and `.../issues/README.md`/`.../storage/README.md` for the full writeup. Re-verified
  green afterward: all repro cases now correctly return `403`, and the rest of the
  milestone's QA suite was re-run with no new regressions.
- **Known gaps, not blockers**: `S3StorageClient` and `OpenAiClassificationClient` are
  implemented and compile but were never live-tested this milestone (no AWS/OpenAI
  credentials available) — both activate purely via config flags
  (`pronto.storage.mode=s3`, `pronto.ai.mode=openai`) once credentials exist, an explicit,
  documented deferral rather than a gap papered over. No `GET /api/issues/{id}` endpoint
  (out of this milestone's scope; a forward dependency for Milestone 3/4's booking flows),
  no rate limiting on `/classify`, no orphaned-upload cleanup job, and the S3
  bucket-privacy policy / AI-suggestion-persistence questions remain genuinely open — see
  `overview.md` §6 and `api-contract-issues.md` §4 for detail.

## Milestone 3 — Standard booking flow

- **Status: COMPLETE (backend), QA-signed-off on branch `MS3`, 2026-08-13.** Not yet
  merged to `main` — merge/push is pending the user's own explicit git operations, not
  implied by this status line. `bookings` (Standard path) and a minimal `availability`
  slice implemented per `docs/architecture/api-contract-bookings.md`, plus one `issues`
  package addition (`GET /api/issues/{id}`); QA validated live against a real Postgres
  instance with zero bugs found — see "QA summary" below. **Frontend booking-flow screens
  (professional listing, slot picker, tracking screen) are intentionally deferred**,
  consistent with the rest of `frontend/` — not a gap in this milestone's backend
  completion.
- **Scope actually built**: two Flyway migrations (`V11__alter_orders_status_add_rejected.sql`,
  a pre-existing gap fix adding the genuine 7th `order_status` value `REJECTED`;
  `V12__add_slot_id_to_orders.sql`, new for this milestone, adds the nullable
  `orders.slot_id` FK used as the sole slot-release lookup mechanism); `GET
  /api/issues/{id}` plus the `IssuesWebConfig` narrowing fix (blanket `/api/issues/**` →
  the two literal `CUSTOMER`-only Milestone 2 paths, leaving the new either-role endpoint
  ungated at the route level); the 8 `bookings` endpoints (`GET
  /api/bookings/professionals`, `GET /api/bookings/professionals/{professionalId}/slots`,
  `POST /api/bookings/orders`, `POST /api/bookings/orders/{orderId}/accept`, `POST
  /api/bookings/orders/{orderId}/reject`, `POST /api/bookings/orders/{orderId}/cancel`,
  `GET /api/bookings/orders/{orderId}`, `GET /api/bookings/orders/me`); the 2
  `availability` endpoints (`POST /api/availability/slots`, `GET
  /api/availability/slots/me`); and the two per-package `WebMvcConfigurer`s
  (`bookings.config.BookingsWebConfig`, with its two precisely-scoped
  `RoleRequiredInterceptor` registrations since this package mixes `CUSTOMER`-only,
  `PROFESSIONAL`-only, and either-role routes in one package;
  `availability.config.AvailabilityWebConfig`, a single blanket-pattern registration since
  both its endpoints share one role). See `backend/src/main/java/com/pronto/bookings/README.md`,
  `.../availability/README.md`, and `.../issues/README.md` for full detail.
- **Acceptance criteria**: full Standard path works end-to-end per PRD §3.4, including the
  reject → return-to-list branch. **Met** — professional listing (filtered by category,
  excluding soft-deleted professionals) → slot selection → order creation (atomic slot
  claim + issue `OPEN → BOOKED` transition) → professional accept/reject → customer
  tracking (`GET /api/bookings/orders/{orderId}`, polling-ready) → either party's cancel
  (state/actor-dependent, per §2.7 of the contract doc) all verified against a real
  Postgres instance, backend-only (no frontend UI this milestone).
- **QA summary**: live-validated against a real Postgres instance — the full happy-path
  Standard flow (professional listing → slot listing → create order → professional
  accept → customer tracking, `orders`/`availability_slots`/`issues` rows all transitioning
  correctly at each step), the reject → return-to-list branch (professional reject →
  slot released → issue reverts to `OPEN` → customer re-lists professionals and re-books,
  including re-booking the same professional who just rejected), cancel edge cases (the
  full actor/state permission matrix from §2.7 of the contract doc: customer cancel from
  `PENDING`/`CONFIRMED`, professional cancel from `CONFIRMED` only, professional attempting
  cancel on a `PENDING` order correctly rejected with `409 ORDER_NOT_CANCELLABLE` since
  `reject` is the only valid action there, cancel on terminal-state orders correctly
  rejected), and ownership/role enforcement on every new endpoint (wrong-role and
  wrong-party callers correctly `403`/`409` across all 8 `bookings` endpoints, the 2
  `availability` endpoints, and `GET /api/issues/{id}`'s dual `CUSTOMER`-ownership /
  `PROFESSIONAL`-order-existence authorization paths). **Zero bugs found**, no regressions
  to Milestone 0-2 (auth, issue creation/classification, image upload all re-verified
  working). Live-validated against a real Postgres instance throughout, not just unit
  tests, matching the rigor of the Milestone 1/2 QA passes.
- **Deferred / explicitly out of scope, per the contract doc**: professional viewing an
  issue's photos before deciding accept/reject remains genuinely unresolved (contract doc
  §6 item 3 / §7) — `GET /api/storage/images/{key}` stays `CUSTOMER`-only in every
  milestone so far, not built or designed here, not blocking (the PRD's Standard flow
  doesn't require photo access before accept/reject). `ON_THE_WAY`/`COMPLETED` job-status
  progression is confirmed Milestone 6 scope (contract doc §6 item 4), not built here —
  this milestone covers request/accept/reject/cancel/track through `CONFIRMED` plus the
  terminal `CANCELLED`/`REJECTED` statuses only. Payment processing and GPS/live location
  tracking remain permanent v1.0 exclusions (not milestone-specific deferrals) — the
  tracking endpoint (§2.8) is status-only, no map/location data.

## Milestone 4 — SOS booking flow

- **Status: COMPLETE (backend), QA-signed-off on branch `MS4`, 2026-08-13.** Not yet
  merged to `main` (nor is `MS3`) — merge/push is pending the user's own explicit git
  operations, not implied by this status line. `bookings` (SOS path) and the
  `availability` SOS-toggle slice implemented per `docs/architecture/api-contract-bookings.md`
  §2.12-2.15 (the same contract doc as Milestone 3, extended in place rather than forked
  into a new file — titled "Milestones 3 & 4" as of this pass); QA validated live against a
  real Postgres instance with one bug found and fixed mid-milestone (a JSON-boolean-
  coercion bug on the new SOS-availability toggle) — see "QA summary" below. **Frontend
  booking-flow screens remain entirely deferred**, consistent with Milestones 1-3 — not a
  gap in this milestone's backend completion.
- **Scope actually built**: two new `bookings` endpoints (`GET
  /api/bookings/sos-professionals?issueId=`, `POST /api/bookings/sos-orders`) plus a fix to
  the three existing Milestone 3 `BookingsService` methods (`listProfessionals`,
  `listSlots`, `createOrder`) adding a step that checks `issue.urgencyType` matches the
  endpoint's booking path (`409 ISSUE_URGENCY_MISMATCH` otherwise) — closing a gap those
  three endpoints shipped with in Milestone 3 (they never validated `urgencyType` at all;
  see the contract doc's §3.10/§6 item 5 for why this was fixed now rather than left open).
  New DTO `bookings/dto/CreateSosOrderRequest.java` (one field fewer than
  `CreateOrderRequest` — no `slotId`, SOS has no slot selection). `ProfessionalListingRepository`
  gained `listSosAvailableByCategory` (joins `professionals`/`users`/`sos_availability`,
  same soft-delete exclusion and `base_price ASC` ordering as the Standard listing query).
  `BookingsWebConfig` gained two new literal path patterns
  (`/api/bookings/sos-professionals`, `/api/bookings/sos-orders`) on its existing
  `CUSTOMER`-scoped `RoleRequiredInterceptor` registration — its literal-list design (chosen
  in Milestone 3 because this package mixes roles per-route) doesn't pick up new routes via
  a wildcard the way `availability`'s config does. In `availability`: two new endpoints
  (`PUT`/`GET /api/availability/sos-availability`), new DTOs
  `availability/dto/SosAvailabilityRequest.java`/`SosAvailabilityResponse.java`,
  `SosAvailabilityRepository` gained an `updateAvailability` unconditional-`UPDATE` method
  (deliberately not the guarded-transition pattern used everywhere else in this doc — see
  `availability/README.md` for why). `AvailabilityWebConfig` needed **no** change — its
  existing blanket `/api/availability/**` pattern already covered the new routes. Two new
  shared error codes in `common.exception.ErrorCode`: `ISSUE_URGENCY_MISMATCH` (409),
  `SOS_PROFESSIONAL_UNAVAILABLE` (409). **No new Flyway migration this milestone** —
  verified directly against the applied migration history: `sos_availability` (`V13`),
  nullable `orders.slot_id`/`booked_end` (`V12`/`V8`), and the 7-value `order_status` `CHECK`
  including `REJECTED` (`V11`) all already existed ahead of this milestone (`V13` was
  applied as a pre-Milestone-4 schema-gap fix, already reflected in `availability/README.md`'s
  prior status line). `accept`/`reject`/`cancel`/`GET .../{orderId}`/`GET .../me`
  (§2.5-§2.9) received **zero code changes** — confirmed by QA to already generalize
  correctly to SOS orders (nullable `slotId`/`bookedEnd` flow through cleanly; the
  unconditional `releaseSlot(order.getSlotId(), now)` call is a safe no-op when `slotId` is
  `null`). See `backend/src/main/java/com/pronto/bookings/README.md` and
  `.../availability/README.md` for full detail.
- **Acceptance criteria**: full SOS path works end-to-end per PRD §3.5, including the
  reject/unavailable → return-to-list-or-no-professional-message branch. **Met** — SOS
  professional listing (filtered by category **and** live `sos_availability.is_available =
  true`, excluding soft-deleted professionals) → order creation against a specific
  currently-available professional (no slot selection; `bookedStart = now()`, `bookedEnd =
  NULL`, `slotId = NULL`) → professional accept/reject (the exact same endpoints Standard
  orders use, unmodified) → customer tracking, all verified against a real Postgres
  instance, backend-only. Both halves of PRD §3.5.6's "rejects or becomes unavailable"
  requirement are implemented: "rejects" maps to the existing `reject` endpoint once an
  order already exists; "becomes unavailable" maps to a `409 SOS_PROFESSIONAL_UNAVAILABLE`
  at order-creation time (a plain read-check of `sos_availability`, not an atomic claim —
  see the contract doc §2.13/§3.11 for the full design reasoning) rather than a reactive
  sweep over already-`PENDING` orders — a deliberate design decision, not an oversight.
- **QA summary**: live-validated against a real Postgres instance (fresh schema, all 13
  migrations applied cleanly) — the full SOS happy path with row-level state verification at
  every step (list SOS-available professionals → professional toggles availability on →
  list again reflects the toggle → customer creates an SOS order → professional accepts →
  customer polls tracking), both branches of PRD §3.5.6 (professional reject via the reused
  Milestone 3 `reject` endpoint; "becomes unavailable" verified as a clean `409
  SOS_PROFESSIONAL_UNAVAILABLE` at order-creation time with no orphaned `orders` rows and no
  incorrect `issues`/`sos_availability` status flips), the no-available-professional case
  (an empty list is a valid `200`, not an error), cross-path `ISSUE_URGENCY_MISMATCH`
  validation in both directions (Standard endpoints correctly reject SOS-flagged issues and
  vice versa, all 5 endpoint combinations: §2.2/§2.3/§2.4 against an SOS issue, §2.12/§2.13
  against a Standard issue), full ownership/role enforcement on all 4 new endpoints, the
  SOS-availability toggle's semantics (idempotent, no state-guard needed, verified against
  both prior states), and a full regression pass confirming **zero breakage** to Milestone
  1-3 — including explicitly confirming `accept`/`reject`/`cancel`/`GET .../{orderId}`/`GET
  .../me` behave identically for SOS vs. Standard orders, since none of those five endpoints
  received any code change this milestone. **One bug found mid-milestone**: a numeric JSON
  value (e.g. `1`/`0`) sent to `PUT /api/availability/sos-availability`'s `isAvailable`
  field was silently coerced to a boolean by Jackson's default lenient scalar coercion,
  rather than being rejected as `400 VALIDATION_ERROR` per the contract doc's explicit field-
  validation rule (§2.14). Fixed via a narrowly-scoped custom Jackson deserializer
  (`availability.dto.StrictBooleanDeserializer`, applied only to
  `SosAvailabilityRequest.isAvailable` via `@JsonDeserialize`) — deliberately **not** a
  global `ObjectMapper` coercion-config change, to avoid any risk of altering Milestone 1-3's
  already-shipped Jackson behavior on other endpoints' `Boolean` fields. Re-verified fixed
  with no side effects to any other endpoint. **Final QA verdict: full sign-off, zero known
  open bugs.**
- **Known gaps/deferred, carried forward accurately from Milestone 3 (checked, still true)**:
  `ON_THE_WAY`/`COMPLETED` job-status progression remains Milestone 6 scope, unchanged by
  this milestone — SOS orders reach the same `CONFIRMED` ceiling Standard orders did in
  Milestone 3. The `PENDING`-order timeout/expiry sweep remains Milestone 5 scope — an SOS
  order stuck `PENDING` because its professional went unavailable mid-request has no faster
  resolution than normal polling (`GET /api/bookings/orders/{orderId}`) until that sweep
  exists; this is the accepted, explicitly-documented consequence of the contract doc's §3.11
  design decision, not a newly-discovered gap. Professional-viewing-issue-images remains open
  and unbuilt (unchanged from Milestone 3, contract doc §6 item 3 / §7). Frontend
  booking-flow screens remain entirely deferred project-wide, consistent with Milestones
  1-3.

## Milestone 5 — Notifications & real-time status

- **Status: COMPLETE (backend), QA-signed-off on branch `MS5`, 2026-08-13.** Not yet merged
  to `main` (nor are `MS3`/`MS4`) — merge/push is pending the user's own explicit git
  operations, not implied by this status line. `notifications` package implemented per
  `docs/architecture/api-contract-notifications.md`, plus the specific hook points that doc
  required adding to the already-shipped `bookings` package; QA validated live against a
  real Postgres instance with one bug found and fixed — during the lead's own review of the
  coding agent's output, *before* QA's formal pass ran — see "QA summary" below.
  **Frontend notification-bell/tracking screens remain entirely deferred**, consistent with
  Milestones 1-4 — not a gap in this milestone's backend completion.
- **Scope actually built**: one Flyway migration
  (`V14__alter_notifications_message_type_add_rejected.sql`, a pre-existing schema-gap fix —
  same category as `V11`/`V13` — adding the missing `'ORDER_REJECTED'` value to
  `notifications.message_type`'s `CHECK`, which `V9` never actually included despite
  `data-model.md` §2.10 already documenting it as present); the 3 `notifications` endpoints
  (`GET /api/notifications`, `POST /api/notifications/{id}/read`, `POST
  /api/notifications/read-all`); `NotificationService`/`NotificationServiceImpl`
  (`recordOrderNotification`, the `bookings → notifications` call boundary, §4.1); two
  `@Scheduled` jobs (`EmailDispatchJob`, 20s, dispatches the `EMAIL`-channel `PENDING` queue
  via a new `EmailSender.sendOrderStatusEmail` method; `OrderExpirySweepJob`, 60s, sweeps
  `PENDING` orders past their per-urgency-type timeout to `EXPIRED`); `config.SchedulingConfig`
  (`@EnableScheduling`, localized to this package). In the already-shipped `bookings`
  package: a new required `NotificationService` constructor dependency on `BookingsService`;
  five new `recordOrderNotification` call sites (`createOrder`/`createSosOrder`/`accept`/
  `reject`/`cancel`); two new `BookingsService` methods (`findExpiredOrderCandidateIds`,
  `expireIfPending`) and two new `OrderRepository` methods (`findPendingExpiryCandidateIds`,
  `expireIfPending`) backing the sweep; two new hardcoded timeout constants
  (`STANDARD_PENDING_TIMEOUT = 15 min`, `SOS_PENDING_TIMEOUT = 5 min` — **decided**, the
  user's own sign-off at this milestone's kickoff, no longer a pending recommendation). One
  new `issues.repository.IssueRepository` method (`expireIfBooked`, mirroring the existing
  `bookIfOpen`/`revertToOpen` guarded-transition pattern). `auth.email.EmailSender` extended
  with a second method, `sendOrderStatusEmail` (decided: extend the existing interface
  rather than introduce a second, `notifications`-owned one — `LoggingEmailSender` remains
  the sole implementation, logs at `INFO`, sends nothing real). New `application.yml` config
  block, `pronto.email.mode` (default `log`; `smtp` not built this milestone — no source
  document names a real email provider, unlike MS2's OpenAI/S3). **No new `ErrorCode`
  values** — audited against every behavior branch in the contract doc; "not found"/"not
  yours" reuse the existing `NOT_FOUND`/`FORBIDDEN` codes. See
  `backend/src/main/java/com/pronto/notifications/README.md` and
  `.../bookings/README.md`'s new Milestone 5 additions for full detail.
- **Acceptance criteria**: booking status changes reach the relevant customer/professional
  within the PRD's ~1s target; tracking screen updates without a manual refresh. **Met, with
  a distinction worth stating explicitly** (per `api-contract-notifications.md` §4.5): the
  PRD's ~1s target governs *propagation* latency — how fast an **already-changed** row
  reaches a client already polling `GET /api/bookings/orders/{orderId}`/`GET
  /api/notifications` every 3-5s, per the existing short-polling design (unchanged this
  milestone). It says nothing about *detection* latency — how fast the backend notices a
  `PENDING` order should expire in the first place. The 60s `OrderExpirySweepJob` interval
  governs detection latency only, for the one new status transition this milestone
  introduces (`PENDING → EXPIRED`); once the sweep *does* transition an order, the existing
  3-5s polling mechanism picks it up with the PRD's normal ~1s-class propagation
  characteristics, unaffected by the sweep's own 60s cadence. Worst case, an order sits up to
  60s past its 15-/5-minute timeout before the sweep acts — a <7% overshoot on the tightest
  (5-minute SOS) case, judged negligible and not a violation of the ~1s target, which was
  never about this kind of latency to begin with.
- **QA summary** (method: live validation against a real Postgres instance — docker-compose
  `pronto-postgres`, migrated through `V13` from prior sessions, built via `mvnd clean
  package`, booted the jar, drove every scenario through the real HTTP API with `curl`,
  verified state via direct `psql` queries at every step, not just HTTP status codes):
  - **Migration (`V14`)**: confirmed pre-state lacked `ORDER_REJECTED` in the `CHECK`
    constraint, confirmed Flyway applied `V14` cleanly (schema version 14), confirmed the
    `CHECK` now includes it, functionally confirmed via a live `reject` call.
  - **Notification creation on every transition**: exercised Standard
    (`createOrder`→accept, →reject, →accept→cancel-by-customer,
    →accept→cancel-by-professional) and SOS (`createSosOrder`→accept→cancel-by-customer)
    paths against real orders, verified via direct DB query: `ORDER_CREATED`→professional's
    `user_id` (both Standard and SOS), `ORDER_CONFIRMED`/`ORDER_REJECTED`→customer,
    `ORDER_CANCELLED`→the *non-acting* party (professional when customer cancels, customer
    when professional cancels). Confirmed the reverse recipient never fires. Zero bugs.
  - **In-app feed endpoints**: `GET /api/notifications` (`IN_APP`-only, correct ordering,
    correct `unreadCount`), `unreadOnly=true` filter, `POST /{id}/read` (idempotent —
    verified via DB that `read_at` doesn't change on a second call), `403` on someone else's
    notification, `404` on a nonexistent id, `404` (not `500`) on a malformed id (the fixed
    bug, below), `POST /read-all` (correct count, doesn't touch other users' rows). Zero
    bugs.
  - **Email dispatch job**: all `PENDING` `EMAIL` rows transitioned to `SENT` with `sent_at`
    populated within ~15-20s (consistent with the 20s `fixedDelay`), `[MOCK EMAIL]` log
    lines confirmed with correct recipient/subject/body content. End-of-session state: 22
    `EMAIL` rows, all `SENT`, zero stuck in `PENDING`/`FAILED`. Pass.
  - **Expiry sweep (highest-risk item)**: backdated real `orders.created_at` via direct SQL
    to simulate timeout, waited for the real 60s `@Scheduled` sweep. Verified precisely at
    both boundaries: Standard backdated 16min → `EXPIRED`; Standard backdated 10min → stays
    `PENDING` (not over-eager); SOS backdated 6min → `EXPIRED`; SOS backdated 3min → stays
    `PENDING`. Full side-effect verification on both expired orders: `orders.order_status =
    'EXPIRED'` + `updated_at` bumped, `issues.status = 'EXPIRED'`, Standard slot released
    (`availability_slots.is_available` back to `true`), SOS's `slot_id` correctly `NULL`
    (safe no-op), `ORDER_EXPIRED` notification for the customer only (both channels) with
    **zero** such rows for the professional (customer-only design holds). "Lost the race"
    case verified: an order backdated past timeout but accepted via the real API *before*
    the sweep ran stayed `CONFIRMED`, not flipped, no exception. Also incidentally observed
    the sweep correctly auto-fire on genuinely stale `PENDING` orders left over from the MS4
    QA session at app startup. Zero bugs — the strongest-verified part of the milestone.
  - **Full regression pass (MS0-4)**: `/actuator/health`, `/api/users/me`, issue creation +
    mock AI classification, image upload round-trip, Standard professional/slot listing
    (soft-delete exclusion still correct), SOS availability toggle/listing, error-code
    taxonomy spot-checks (`409 ORDER_NOT_PENDING`, `409 ISSUE_NOT_BOOKABLE`) all still
    correct. Zero regressions. (This regression pass also reconfirmed the
    `EXPIRED`-issue-can't-be-rebooked gap live — not a new finding, restates the
    already-flagged `data-model.md` §4 item, see "Known gaps" below.)
  - **One bug found**, caught during the lead's own review of the coding agent's output
    *before* this formal QA pass ran (so QA's own pass itself found zero new defects, but the
    bug is recorded here for the same reason MS2's role-check-ordering bug and MS4's
    JSON-boolean-coercion bug are — a real bug caught and fixed, not swept under the rug):
    `NotificationController`'s `{id}` path-variable handling initially used a typed
    `@PathVariable Long`, with an incorrect Javadoc claim that Spring's default
    type-mismatch handling would produce a `4xx` on a malformed value. In fact this would
    have fallen through to `GlobalExceptionHandler`'s generic catch-all and returned `500
    INTERNAL_ERROR`, not a `4xx`. Fixed to manually parse the id, matching
    `IssuesController`'s established convention, guaranteeing `404 NOT_FOUND` on a malformed
    value. QA live-verified the fix: `POST /api/notifications/abc/read` → `404`, not `500`.
  - **Everything else matched the contract doc precisely** — QA's report states this
    explicitly for the entity, repository, service, both scheduler jobs, the `V14`
    migration, the `EmailSender` extension, the `application.yml` config block, and the
    `bookings`/`issues` repository additions, and the trigger→recipient mapping.
  - **Final verdict: full sign-off, zero known open bugs.** The one bug found (the
    malformed-id `500`-vs-`404` issue) was caught and fixed before this formal QA pass ran;
    QA's own pass itself found zero new defects.
- **Known gaps, not blockers**:
  - **The `EXPIRED`-issue-cannot-be-rebooked gap** (`docs/architecture/data-model.md` §4,
    `docs/architecture/api-contract-notifications.md` §7): an issue that reaches `EXPIRED`
    has no endpoint that reopens it to `OPEN`, so `createOrder`/`createSosOrder`'s
    `issue.status == 'OPEN'` requirement makes an `EXPIRED` issue a practical dead end via
    the existing booking endpoints (the customer's only workaround is creating an entirely
    new `issues` row for the same problem). Reconfirmed as still-accurate and non-blocking
    during QA's live regression pass this milestone (not a new finding) — needs a
    `pronto-lead`/user decision (a "reopen" endpoint? treat `EXPIRED` as book-able too?
    accept the new-issue workaround as intended?), not resolved by this milestone.
  - No retry/backoff for `FAILED` email rows — `EmailDispatchJob` marks a row `FAILED` on any
    exception and moves on; nothing retries it. Accepted MVP gap, an M7 hardening candidate
    if email reliability becomes a concern once a real provider exists.
  - No multi-instance email-dispatch atomic "claim" step — `EmailDispatchJob` has no per-row
    claim before sending; two concurrent instances could double-send. Accepted MVP gap under
    the current single-instance deployment assumption (`overview.md` §6); would need an
    interim `delivery_status` value (a `V15` `CHECK` addition) before safe under horizontal
    scaling.
  - `ON_THE_WAY`/`COMPLETED` notification hooks are not wired — no `BookingsService` method
    producing those statuses exists yet (Milestone 6's job); adding a hook now would be dead
    code. Milestone 6 adds exactly one `recordOrderNotification(...)` call per new transition
    method, following the identical pattern this milestone established.
  - `auth`'s `EMAIL_VERIFICATION` message type is not retrofitted into this table this
    milestone — an in-app notification for an event that occurs before the user has a usable
    session has no real use case; `EMAIL_VERIFICATION` stays in the schema/enum for
    completeness and any future retrofit, but nothing writes it yet.
  - No SMS/push channels (unchanged, out of scope per `overview.md` §2). No rate limiting on
    any endpoint in this doc (low risk, noted only for completeness). Exact notification
    copy/Hebrew localization remains genuinely open, needs product/UX input (§7 of the
    contract doc).

## Milestone 6 — Professional dashboard

- **Status: COMPLETE (backend), QA-signed-off on branch `MS6`, 2026-08-13.** Not yet merged
  to `main` (nor are `MS3`/`MS4`/`MS5`) — merge/push is pending the user's own explicit git
  operations, not implied by this status line. Job-status progression endpoints
  (`ON_THE_WAY`/`COMPLETED`) implemented in the `bookings` package per
  `docs/architecture/api-contract-bookings.md` §2.16-2.17 (the same contract doc as
  Milestones 3 & 4, extended in place rather than forked into a new file — titled
  "Milestones 3, 4 & 6" as of this pass); the doc's §8 separately reviews and confirms the
  other two acceptance-criterion pieces ("manage availability," "see incoming requests")
  need no new endpoint at all, since the existing Milestone 3/4 surface already covers them.
  QA validated live against a real Postgres instance with **zero bugs found** — see "QA
  summary" below. **Frontend professional-dashboard screens remain entirely deferred**,
  consistent with Milestones 1-5 — not a gap in this milestone's backend completion.
- **Scope actually built**: two new `bookings` endpoints (`POST
  /api/bookings/orders/{orderId}/on-the-way`, `CONFIRMED → ON_THE_WAY`, professional-only;
  `POST /api/bookings/orders/{orderId}/complete`, `ON_THE_WAY → COMPLETED`,
  professional-only). Both follow the exact same shape as the existing `accept`/`reject`
  endpoints: same ownership check (`ProfessionalRepository.findByUserId`), a single guarded
  `UPDATE ... WHERE order_status = <expected>` transition
  (`OrderRepository.onTheWayIfConfirmed`/`completeIfOnTheWay`, new this milestone), and a
  trailing `notificationService.recordOrderNotification(...)` call to the **customer**
  (`ORDER_ON_THE_WAY`/`ORDER_COMPLETED` respectively) — reusing the exact notification
  mechanism Milestone 5 established, no new call boundary. `complete` additionally
  transitions the issue via a new `IssueRepository.completeIfBooked` method
  (`UPDATE issues SET status = 'COMPLETED' ... WHERE status = 'BOOKED'`), mirroring
  `expireIfBooked`'s exact shape and, like `expireIfPending`'s call to it, not checked for a
  `0`-row result — the single-active-order-per-issue invariant (`data-model.md` §3 item 8,
  §3.3 of the contract doc) guarantees this always affects exactly 1 row when reached. Two
  new `common.exception.ErrorCode` values, both `409`: `ORDER_NOT_CONFIRMED` (`on-the-way`
  called on a non-`CONFIRMED` order) and `ORDER_NOT_ON_THE_WAY` (`complete` called on a
  non-`ON_THE_WAY` order — this is also the code returned for the deliberately-disallowed
  `CONFIRMED → COMPLETED` skip-ahead attempt, no separate code distinguishes "you skipped a
  step" from "wrong state for any other reason"). `BookingsWebConfig`'s existing
  `PROFESSIONAL`-scoped `RoleRequiredInterceptor` registration gained two more literal path
  patterns (`/api/bookings/orders/*/on-the-way`, `/api/bookings/orders/*/complete`) — its
  literal-list design (chosen in Milestone 3 because this package mixes roles per-route)
  doesn't pick up new routes automatically the way a wildcard would. **No `availability`
  package changes** — confirmed and reasoned explicitly in the contract doc §8, not silently
  skipped (see "Known gaps" below for the "no slot edit/delete" decision). **No new Flyway
  migration** — verified directly against `backend/src/main/resources/db/migration/`, which
  contains exactly `V1`-`V14`: `orders.order_status`'s `CHECK` has allowed `ON_THE_WAY`/
  `COMPLETED` since the original `V8__create_orders.sql`, `notifications.message_type`'s
  `CHECK` (as amended by `V14`) has allowed `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` since the
  original `V9__create_notifications.sql`, and `issues.status`'s `CHECK` has allowed
  `COMPLETED` since the original `V6__create_issues.sql` — this milestone is the first to
  *reach* these already-tolerated values via a real endpoint, not the first to need the
  schema to allow them. **No new DTO** — `OrderResponse`/`OrderStatus` already carried
  `ON_THE_WAY`/`COMPLETED` as unused enum values. See
  `backend/src/main/java/com/pronto/bookings/README.md` for full detail.
- **Acceptance criteria**: "a professional can manage availability, see incoming requests,
  and progress a job through its statuses" — **all three met**, but only the third required
  new backend work:
  - **Manage availability**: already satisfied by the existing Milestone 3/4 surface
    (`POST`/`GET /api/availability/slots`+`/me`, `PUT`/`GET
    /api/availability/sos-availability`) — reviewed explicitly this milestone
    (`api-contract-bookings.md` §8.1/§8.2) and confirmed sufficient; slot edit/delete was
    explicitly considered and explicitly declined (see "Known gaps" below), not silently
    left out.
  - **See incoming requests**: already satisfied by `GET
    /api/bookings/orders/me?status=PENDING` (§2.9, Milestone 3) — confirmed this milestone
    (§8.1/§8.3) to already be exactly the right query shape, generic across Standard and SOS
    orders.
  - **Progress a job through its statuses**: the one genuinely new piece — `accept`/`reject`
    (Milestone 3) plus `on-the-way`/`complete` (this milestone) now cover the full
    `PENDING → CONFIRMED → ON_THE_WAY → COMPLETED` sequence end-to-end, with `cancel`
    remaining reachable from `PENDING`/`CONFIRMED`/`ON_THE_WAY` throughout, per PRD §3.6.1's
    named status sequence. **Met** — verified against a real Postgres instance, backend-only
    (no dashboard UI this milestone).
- **QA summary**: live-validated against a real Postgres instance (method: live HTTP + direct
  `psql` verification at every step, not just status codes) —
  - **Happy path, both booking types**: drove a Standard order and an SOS order each all the
    way from creation through `accept` → `on-the-way` → `complete`, verifying at every step
    via direct SQL: `orders.order_status`/`updated_at` transitions correctly at each hop,
    `issues.status` stays `BOOKED` through `CONFIRMED`/`ON_THE_WAY` and only flips to
    `COMPLETED` on the final `complete` call (never earlier), and the correct `OrderResponse`
    shape/values at each step.
  - **Guard-violation / skip-ahead cases**: `on-the-way` called against a `PENDING`,
    `ON_THE_WAY` (already progressed), `COMPLETED`, `CANCELLED`, and `REJECTED` order each
    correctly returned `409 ORDER_NOT_CONFIRMED`. `complete` called against a `PENDING` order
    and — the deliberately-disallowed skip-ahead case — a still-`CONFIRMED` order (never
    routed through `on-the-way`) each correctly returned `409 ORDER_NOT_ON_THE_WAY`,
    confirming `ON_THE_WAY` is enforced as a genuine mandatory intermediate step by the code,
    not just documented as one in the contract doc. `complete` against an already-`COMPLETED`
    order and a `CANCELLED` order also correctly `409`'d.
  - **Actor/role enforcement**: a customer calling either `on-the-way` or `complete`
    correctly `403`'d (route-level `RoleRequiredInterceptor` rejection); a professional who
    is not the order's own assigned professional calling either endpoint correctly `403`'d
    (service-layer ownership check) on both a Standard and an SOS order.
  - **`cancel` non-regression from `ON_THE_WAY`**: confirmed `cancel` (§2.7, unmodified this
    milestone) still works correctly from `ON_THE_WAY` for both actors (customer and
    professional), with no change to its existing `PENDING`/`CONFIRMED` behavior — the actor/
    state permission matrix from Milestone 3 held unchanged.
  - **Notification targeting**: direct `psql` verification that `ORDER_ON_THE_WAY`/
    `ORDER_COMPLETED` notification rows were created for the customer only, on both a
    Standard and an SOS order — zero such rows were ever created for the professional across
    the entire session, confirming the "acting party doesn't need telling about their own
    action" design holds for these two new transitions exactly as it already did for
    `accept`/`reject`/`cancel`.
  - **Full regression pass, Milestones 0-5**: `/actuator/health`, auth, issue creation +
    classification, image upload, Standard/SOS professional and slot listing, the SOS
    availability toggle, and — the highest-risk item — the `PENDING`-order expiry sweep
    (Milestone 5), re-verified to correctly leave `CONFIRMED`/`ON_THE_WAY`/`COMPLETED` orders
    alone (the sweep only ever targets `PENDING` orders, and this milestone introduced no
    code path that could move an order back into `PENDING`). All still correct, zero
    regressions found.
  - **Final verdict: zero bugs found, full sign-off.**
- **Known gaps, not blockers**:
  - **The `EXPIRED`-issue-cannot-be-rebooked gap** (`docs/architecture/data-model.md` §4,
    `docs/architecture/api-contract-notifications.md` §7) remains open and is **confirmed
    unaffected by this milestone** — `on-the-way`/`complete` only ever read/write orders that
    are already `CONFIRMED`/`ON_THE_WAY`, never `OPEN`/`EXPIRED` issues, so neither new
    endpoint adds, removes, or narrows this gap in any way (verified explicitly in the
    contract doc §9, not just asserted). Still needs a `pronto-lead`/user decision, unchanged
    from Milestone 5.
  - **No slot edit/delete added to `availability`** — restated explicitly as a **judgment
    call, not a gap**: reviewed this milestone (`api-contract-bookings.md` §8.2) against the
    "manage availability" acceptance criterion and PRD text, and deliberately not built (no
    PRD text mandates it, no load-bearing functional gap exists without it, and frontend is
    out of scope project-wide anyway). If a future UX review decides otherwise, the addition
    would be a small independent slice (`DELETE /api/availability/slots/{slotId}`), not
    designed here.
  - **Frontend professional-dashboard UI remains entirely deferred project-wide**, consistent
    with every prior milestone — not built or designed here, pending the user's design-system
    decision.
  - Professional-viewing-issue-images (contract doc §6 item 3 / §7) remains open and unbuilt,
    unchanged from every prior milestone.

## Milestone 7 — Hardening & QA pass

- **Scope**: performance validation against PRD §5.1 targets (2s load, 1s status update,
  1,000 concurrent users, 5s image upload), security checklist (TLS 1.3, password
  storage, lockout, data deletion), full cross-flow regression, final documentation sync
  across all packages.
- **Acceptance criteria**: QA sign-off against every PRD Must-Have and Should-Have
  requirement; no known critical defects open; every package `.md` current.

### Results (2026-08-14)

Full spec executed: `docs/architecture/hardening-plan.md`.

- **§1 Performance** — all thresholds passed. Note §1.1 (PRD §5.1.1, 2s max screen-load) is
  necessarily a **backend-only proxy** measurement, not a literal end-to-end screen-load
  test — no frontend exists yet in this project. Full methodology, raw results, and k6
  output are archived under `docs/qa/ms7/perf/`, not restated here.
- **§2 Security** — every checklist item passed or was reviewed (TLS/HTTPS is a
  config/design review only, per the plan's own explicit caveat — no deployed environment
  exists to live-test against). Zero regressions found across MS1-6 behavior re-verified
  this pass.
- **§3 Regression matrix** — all 9 cross-flow rows passed with zero defects found. Full
  detail lives in QA's own notes, not restated here in full.
- **Two hardening fixes implemented and live-verified** this milestone, both additive with
  no behavior/DTO/config-default changes to existing endpoints: the `JWT_SECRET` fail-fast
  startup guard (hardening-plan.md §5.1) and per-IP auth rate limiting on
  `/api/auth/register`\|`login`\|`verify` (hardening-plan.md §5.2). See
  `backend/src/main/java/com/pronto/auth/README.md` for the as-implemented writeup.

**Open items — pending `pronto-lead`/user sign-off, not decided here** (per the project's
standing rule against silently resolving things that need sign-off; full context for each
is in `hardening-plan.md` §6):

1. §4.1 — the `EXPIRED`-issue-cannot-be-rebooked product decision (reopen endpoint vs.
   relax the booking guard vs. formally accept the new-issue workaround as intended).
2. §2.4 — PRD §5.2.4 "personal data management" scope (does soft-delete + anonymization
   satisfy it, or is a self-service data-export endpoint also expected).
3. §2.5 — AWS root-account credential rotation timing (local dev run config currently uses
   root-account keys; recommendation is to rotate to a scoped IAM user/role, timing is the
   user's call).
4. §4.2/§4.3 — confirmation that deferring email retry/backoff and the multi-instance
   dispatch "claim" mechanism (both recommended as out of Milestone 7 scope) is accepted,
   not silently agreed.
5. §4.4 — confirmation nod only (no new decision) that leaving `availability` without
   slot edit/delete remains correct, as already decided in Milestone 6.
6. §5.5 — pagination. The load test now supplies a real measured data point: the
   professionals-listing payload is approximately 1565 bytes for about 7 professionals in
   one category at current seed scale — comfortably within the "tens of KB, defer" range
   the plan itself set as its own decision threshold. Still needs `pronto-lead`/user to
   formally close this out rather than leaving it open by default.

## Cross-cutting rules for every milestone

- Planning docs (`overview.md`, this file) are updated if a milestone's actual
  implementation diverges from what was designed — divergence is expected and fine, silent
  drift is not.
- QA validates before Lead closes a milestone.
- Documentation's per-package `.md` requirement is part of the milestone's definition of
  done, not a follow-up task.
- No remote git operation (push, merge, PR) at any point without the user's explicit
  approval for that specific action.

## Note on this document's origin

This plan and `overview.md` were produced by the orchestrating session directly (acting
in the `pronto-planning` / `pronto-lead` roles) because the newly-created
`.claude/agents/pronto-*.md` subagents were not yet available to the Agent tool in this
session — the harness's available-agent list appears to be fixed per session and doesn't
pick up new agent files until a new session starts. Once available, future planning/
review passes should go through the actual `pronto-planning`/`pronto-lead` agents rather
than being done inline.
