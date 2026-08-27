# `notifications`

## Purpose

In-app notification feed (the "bell"), email dispatch for order-status changes, and the
`PENDING`-order timeout expiry sweep.

Implements `docs/architecture/api-contract-notifications.md` in full (§1 migration, §3
endpoints, §4 cross-cutting mechanisms).

## Responsibilities

- `GET /api/notifications?unreadOnly={bool}` — the in-app feed/bell. Returns **`IN_APP`**-
  channel rows only (`EMAIL`-channel rows are an internal dispatch-pipeline concern, never
  surfaced here — returning them too would show every event twice), ordered `created_at
  DESC`. `unreadCount` is always computed regardless of the `unreadOnly` filter, so the bell
  badge stays correct even when the list itself is unfiltered. No pagination this milestone
  (consistent with `bookings`/`availability`'s established "no pagination this milestone"
  convention).
- `POST /api/notifications/{id}/read` — marks one notification read. **Idempotent**: a second
  call on an already-read notification is a no-op success, not an error (the *first* read
  time is the meaningful one, never overwritten). `404 NOT_FOUND` if the id doesn't resolve,
  `403 FORBIDDEN` if it belongs to a different user.
- `POST /api/notifications/read-all` — "clear the bell." A judgment call, not explicitly
  requested by any source document (§3.3 of the contract doc flags it as such) — cheap (one
  guarded `UPDATE`) and a near-universal pattern for this UI shape. Returns the
  affected-row count; only touches the caller's own `IN_APP`/unread rows.
- `NotificationService.recordOrderNotification(orderId, recipientUserId, messageType)` — the
  `bookings → notifications` call boundary (§4.1). Called by `bookings.service.BookingsService`
  as the last step of every successful order-lifecycle transition, inside that same
  `@Transactional` method (no outbox pattern, no event bus — a deliberate simplicity call,
  §4.3). Inserts an `IN_APP` row (`delivery_status = SENT` immediately — the row itself being
  queryable *is* delivery) and, **only if `service.NotificationEmailCopy.isEmailable(type)`**,
  an `EMAIL` row (`delivery_status = PENDING`, dispatched later by `EmailDispatchJob`). §4.3's
  original unconditional "two rows per call" is what mailed a customer whose SOS search found
  nobody — see the `NotificationEmailCopy` bullet below. The full trigger→recipient mapping
  (§4.2 of the contract doc):

  | Trigger (in `bookings.service.BookingsService`) | `messageType` | Recipient |
  |---|---|---|
  | `createOrder` / `createSosOrder` | `ORDER_CREATED` | Professional |
  | `accept` | `ORDER_CONFIRMED` | Customer |
  | `reject` | `ORDER_REJECTED` | Customer |
  | `cancel` | `ORDER_CANCELLED` | The *other* party (professional if the customer cancelled, customer if the professional cancelled) |
  | `expireIfPending` (new this milestone) | `ORDER_EXPIRED` | Customer |

  `ON_THE_WAY`/`COMPLETED` transitions have no hook yet — **decided, not an oversight**: no
  `BookingsService` method producing those statuses exists until Milestone 6, so there is no
  call site to attach a hook to (§4.6). `auth`'s registration-verification email is likewise
  **not** retrofitted into this table this milestone — an in-app notification for an event
  that happens before the user has a usable session has no real use case (§6 item 6 of the
  contract doc).
- `NotificationEmailCopy` (`service.NotificationEmailCopy`) — **the allowlist of message types
  that may become email, and the Hebrew copy for each, in one exhaustive `switch`.** A type
  with no copy is a type that is not sent; adding a `NotificationMessageType` is a compile
  error here rather than a silent new customer email. Three types are deliberately off it:
  `SOS_NO_PROFESSIONALS` and `SOS_TEMPORARILY_UNAVAILABLE` (both written by
  `sos.service.SosDispatchService`'s failure branches, seconds after the customer pressed the
  button, while they are watching the live SOS screen that already shows the same outcome —
  neither describes anything a person did) and `EMAIL_VERIFICATION` (`auth.email.OtpMessageCopy`
  owns every word Pronto says about a verification code, and a second blander message alongside
  it would dilute a security signal). The in-app row is written in all three cases; only the
  email goes. **Fixes the incident** where a customer received `"Pronto — Order #null: status
  changed to SOS_NO_PROFESSIONALS"` — three defects in one line (an email for an internal state,
  an internal enum name as customer copy, and an order id for a flow that has no order yet:
  SOS rows carry `related_sos_request_id` and a `NULL` `related_order_id` by design).
- `EmailDispatchJob` (`scheduler.EmailDispatchJob`, `@Scheduled(fixedDelay = 20_000)`) —
  polls the `EMAIL`-channel `PENDING` queue (batch of 50, `idx_notifications_channel_status`,
  oldest first), resolves the recipient's email via `users.repository.UserRepository`, renders
  subject/body via `NotificationEmailCopy` (it composes no copy of its own — string-concatenating
  `messageType`/`relatedOrderId` is exactly what leaked enum names and `Order #null`), calls
  `auth.email.EmailSender.sendOrderStatusEmail(...)`, then marks the row `SENT` (with `sent_at`)
  on success or `FAILED` on any exception (logged at `WARN`, not rethrown — one bad row must not
  block the rest of the batch). A row whose type is not on the allowlist is marked `SUPPRESSED`
  (`V53`) and never sent — a second lock on the same door, and the only thing that stops the
  pre-`V53` rows already in the queue from being delivered on the first poll after deploy.
  `SUPPRESSED` rather than `FAILED` because `FAILED` means a send was attempted and is the
  signal an operator uses to find genuine bugs; `SUPPRESSED` rather than left `PENDING` because
  those are the oldest rows in a `created_at`-ordered batch and would park at the head of the
  queue forever.
- `OrderExpirySweepJob` (`scheduler.OrderExpirySweepJob`, `@Scheduled(fixedDelay = 60_000)`)
  — every 60s, calls `bookingsService.findExpiredOrderCandidateIds()` then
  `bookingsService.expireIfPending(orderId)` for each candidate. The 60s interval does
  **not** contradict the PRD's ~1s status-observability target: that target is about how fast
  an *already-changed* row reaches a polling client, not about how fast the backend notices a
  `PENDING` order should expire in the first place — a detection latency, not a propagation
  latency (§4.5 of the contract doc has the full reasoning). Worst case, an order that hit its
  timeout waits up to another 60s before the sweep transitions it — a <7% overshoot on the
  tightest case (5-minute SOS timeout).
- `config.SchedulingConfig` (`@Configuration @EnableScheduling`) — enables `@Scheduled` for
  this package's two jobs. Deliberately localized here rather than added to
  `ProntoApplication` directly, keeping "this package needs scheduling" scoped to the package
  that actually needs it.
- `V14__alter_notifications_message_type_add_rejected.sql` — adds `'ORDER_REJECTED'` to
  `notifications.message_type`'s `CHECK` constraint, closing a pre-existing schema gap:
  `V9__create_notifications.sql` never actually included it despite `data-model.md` §2.10
  already stating it was "added alongside the new `REJECTED` order status ... to keep this
  1:1 mapping intact." Same category of pre-existing-gap fix as `V11`/`V13`; `V9` was not
  edited in place. Confirmed live: pre-state lacked `ORDER_REJECTED` in the constraint,
  post-`V14` the constraint includes it, functionally exercised via a real `reject` call.
- **A bug was found and fixed here during coding review (pre-QA)**: `NotificationController`'s
  `{id}` path variable was initially a typed `@PathVariable Long`, with an incorrect Javadoc
  claim that Spring's default type-mismatch handling would produce a `4xx` on a malformed
  value. In fact a malformed `{id}` would have fallen through to
  `common.exception.GlobalExceptionHandler`'s generic catch-all and returned `500
  INTERNAL_ERROR`, not a `4xx` — caught in review before QA's formal pass. Fixed by manually
  parsing the id in the controller (`parsePathId`), matching `issues.controller
  .IssuesController`'s established convention, guaranteeing `404 NOT_FOUND` on a malformed
  value (path-referenced ids resolve-or-`404`, per §0 of the contract doc). QA live-verified
  the fix: `POST /api/notifications/abc/read` → `404`, not `500`.

## Key classes

| Class | Role |
|---|---|
| `entity.Notification` | JPA entity for `notifications`. `userId`/`relatedOrderId` are plain FK columns, not associations (same convention as `Order`/`Issue`). No `@PrePersist`/`@PreUpdate` auditing callbacks — `created_at`/`sent_at`/`read_at` are each set explicitly by the service layer at the exact moment they become meaningful, not implicitly on every save. |
| `entity.NotificationChannel` / `entity.NotificationDeliveryStatus` / `entity.NotificationMessageType` | Enums mirroring `channel` (`IN_APP`, `EMAIL`), `delivery_status` (`PENDING`, `SENT`, `FAILED`), and `message_type` (8 values post-`V14`, including `ORDER_REJECTED`) respectively. `NotificationMessageType` is imported directly by `bookings` (§4.1) — the one enum this package exposes across the package boundary. |
| `repository.NotificationRepository` | `JpaRepository`, plus the feed queries (`findByUserIdAndChannelOrderByCreatedAtDesc` / the `AndReadAtIsNull` variant / `countByUserIdAndChannelAndReadAtIsNull`, §3.1), `markAllRead` (guarded `UPDATE`, §3.3), and `findFirst50ByChannelAndDeliveryStatusOrderByCreatedAtAsc` (the email-dispatch queue query, §4.4, uses `idx_notifications_channel_status`). |
| `service.NotificationService` / `service.NotificationServiceImpl` | The interface is the `bookings → notifications` call boundary (§4.1) — deliberately primitive/enum parameters only (`recordOrderNotification(Long orderId, Long recipientUserId, NotificationMessageType messageType)`) so `bookings` never needs to import a `notifications`-owned entity and vice versa. Also backs the three controller endpoints (`getFeed`/`markRead`/`markAllRead`). |
| `scheduler.EmailDispatchJob` | `@Scheduled(fixedDelay = 20_000)`. Depends on `NotificationRepository`, `users.repository.UserRepository`, and `auth.email.EmailSender`. No atomic per-row "claim" step before sending — a flagged, accepted MVP gap under a single-instance deployment assumption (see Assumptions below). |
| `scheduler.OrderExpirySweepJob` | `@Scheduled(fixedDelay = 60_000)`. Depends on `bookings.service.BookingsService` — the deliberate `notifications → bookings` half of the package-level dependency cycle (see Interactions below). |
| `config.SchedulingConfig` | `@Configuration @EnableScheduling`, package-local. |
| `controller.NotificationController` | `/api/notifications` (`GET`), `/{id}/read` (`POST`), `/read-all` (`POST`). No route-level role gate anywhere in this package — every route is either-role, self-scoped by the caller's own `user_id` (§0.1); no `notifications.config.NotificationsWebConfig` exists, a deliberate minimalism call, not an oversight. `{id}` is parsed manually (`parsePathId`) — see the bug writeup above. |
| `dto.NotificationResponse` / `dto.NotificationsListResponse` / `dto.ReadAllResponse` | Wire shapes for the three endpoints. `channel`/`deliveryStatus` are deliberately not included in `NotificationResponse` — internal dispatch-pipeline fields with no meaning to an in-app viewer (every row returned via `GET /api/notifications` is, by construction, `channel = IN_APP`). |

## Interactions with other packages

- **Depended on by `bookings`**: `bookings.service.BookingsService` is constructor-injected
  with `NotificationService` and calls `recordOrderNotification(...)` as the last step of
  `createOrder`/`createSosOrder`/`accept`/`reject`/`cancel`/`expireIfPending`; it also imports
  `NotificationMessageType` directly (no `common`-package indirection, matching this
  codebase's established convention for cross-domain-package service calls — the same
  pattern `issues` already uses to call `ai` directly).
- **Depends on `bookings`**: `OrderExpirySweepJob` calls `BookingsService
  .findExpiredOrderCandidateIds()`/`expireIfPending(Long)` every 60s. Together with the bullet
  above, this forms a **deliberate, flagged `bookings ↔ notifications` package-level
  dependency cycle** — the direct, unavoidable consequence of the sweep-ownership split
  `data-model.md` §3 item 8 and `pronto-lead` already decided (`bookings` owns the domain
  rule/transition; `notifications` owns building/running the `@Scheduled` orchestrator that
  invokes it). Not a Java-level compile cycle in the risky sense (no single class pair
  mutually imports each other — `BookingsService` imports `NotificationService`/
  `NotificationMessageType`; `OrderExpirySweepJob` imports `BookingsService`, two independent
  directed edges), and since this is one Spring Boot module, nothing enforces package
  boundaries at compile time regardless. See `bookings/README.md`'s Interactions section for
  the same writeup from the other side.
- Depends on `auth.email.EmailSender` (extended this milestone with
  `sendOrderStatusEmail`, alongside the pre-existing `sendVerificationCode`) —
  `EmailDispatchJob` calls it directly. This is a `notifications → auth` compile dependency,
  the same kind of direct cross-domain-package call `issues → ai` already established (no new
  pattern).
- Depends on `users.repository.UserRepository` to resolve a notification recipient's email
  address for dispatch (a pre-existing `notifications → users` dependency implied by the
  `user_id` FK, made concrete by this milestone's dispatch job).
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode` — reuses the
  existing `NOT_FOUND`/`FORBIDDEN` codes; no new `ErrorCode` value was needed by this
  package, audited against every behavior branch in the contract doc).
- Read by the frontend's polling status/tracking UI (not built yet — booking-flow and
  notification-bell screens remain deferred project-wide, consistent with Milestones 1-4).

## Data model

Owns the `notifications` table (`docs/architecture/data-model.md` §2.10), as amended by this
milestone's `V14__alter_notifications_message_type_add_rejected.sql` (adds the missing
`ORDER_REJECTED` value to the `message_type` `CHECK` — see Responsibilities above for the
full gap-and-fix writeup). No other schema change was required this milestone —
`related_order_id`, `channel`, `delivery_status`, `read_at`, `sent_at` already existed and
already supported every design decision in the contract doc.

`V53__alter_notifications_delivery_status_add_suppressed.sql` later added `'SUPPRESSED'` to the
`delivery_status` `CHECK`, for rows that were never eligible for delivery on their channel as
opposed to rows whose delivery was attempted and failed. See the `EmailDispatchJob` bullet
above. `delivery_status` is not exposed by `NotificationResponse`, so this is not an API change.

## Assumptions / judgment calls made during implementation

All judgment calls below follow the contract doc's explicitly stated default — no deviation
except the one bug-fix noted in Responsibilities/Status:

- **`POST /api/notifications/read-all` was built as a judgment call**, not explicitly
  requested by any source document — cheap, common UI pattern, per §3.3/§6 item 8 of the
  contract doc.
- **Notification copy — no longer a placeholder.** §7 of the contract doc left it open and
  `EmailDispatchJob` filled the gap with `"Pronto — Order #<id>: status changed to <STATUS>"`,
  built by concatenating the enum constant. That reached real customers in Production: English
  copy in a Hebrew-only product, quoting internal enum names, with a literal `#null` on every
  SOS row. Replaced by `service.NotificationEmailCopy` — Hebrew, per type, written from the
  recipient's point of view, mirroring
  `frontend/src/features/notifications/notificationLabels.ts` (including its two load-bearing
  rules: availability is never described as selection, and the sentence is the recipient's, not
  an observer's). Still not a UX-signed-off copy deck, but no longer a placeholder that leaks
  implementation detail.
- **No real SMTP/SES `EmailSender` implementation was built this milestone.**
  `auth.email.LoggingEmailSender` remains the sole implementation; `pronto.email.mode`
  (`application.yml`) is introduced now for forward compatibility with only `log` as an
  implemented value — no source document names an email provider at all (unlike MS2's
  OpenAI/S3, which only lacked credentials), so writing an untested class against an unpicked
  provider was judged not worth the risk of rework. See `auth/README.md` for the
  `EmailSender` interface itself.
- **`EmailDispatchJob` has no atomic per-row "claim" step before sending** — two backend
  instances polling concurrently could both pick up and send the same `PENDING` row.
  **Accepted MVP gap**: this app is not currently expected to run more than one instance
  (`overview.md` §6). If/when it does, this job needs an interim `delivery_status` claim value
  (a `V15` `CHECK` addition) before it's safe under horizontal scaling. Flagged, not silently
  risked.
- **No retry/backoff for `FAILED` email rows** — a row marked `FAILED` is never retried.
  Accepted MVP gap, flagged as an M7 hardening candidate if email reliability becomes a
  concern once a real provider exists.
- **The `PENDING`-order timeout durations are DECIDED, not a pending recommendation**: 15
  minutes for `STANDARD`, 5 minutes for `SOS` — signed off by the user when this milestone was
  kicked off. Hardcoded `static final Duration` constants in `bookings.service
  .BookingsService` (the package that owns the domain rule), no migration.
- **`issues.status` transitions to `EXPIRED` unconditionally when `expireIfPending` succeeds**
  — not a runtime branch on "did the customer already rebook." See `bookings/README.md`'s
  Assumptions section for the full single-active-order-invariant reasoning (this is a
  `bookings`-owned transition; documented in both places since it matters to both).
- **The malformed-`{id}`-parsing bug** (typed `@PathVariable Long` + an incorrect Javadoc
  claim about Spring's default type-mismatch handling → would have produced `500
  INTERNAL_ERROR` instead of `404 NOT_FOUND`) was caught during the lead's review of the
  coding agent's output, *before* QA's formal pass ran — fixed via manual parsing
  (`parsePathId`), matching `issues.controller.IssuesController`'s convention. QA then
  live-verified the fix as part of its regular pass; QA's own pass itself found zero new
  defects. See Responsibilities above for the full writeup.

## Status

**Implemented and QA-validated in Milestone 5 (Notifications & real-time status)**, on
branch `MS5` (not yet merged to `main`, nor are `MS3`/`MS4` — pending the user's own git
operations), per `docs/architecture/api-contract-notifications.md` and
`docs/architecture/implementation-plan.md`.

Every component in this package matched the contract doc precisely — QA's report states
this explicitly for the entity, repository, service, both scheduler jobs, the `V14`
migration, the `EmailSender` extension, the `application.yml` config block, and the
`bookings`/`issues` repository additions this package's design required. The one deviation
from the contract doc — the malformed-`{id}`-parsing bug — is a real bug caught and fixed,
documented above and in `docs/architecture/implementation-plan.md`'s Milestone 5 entry, not
swept under the rug.

**QA summary** (method: live validation against a real Postgres instance — docker-compose
`pronto-postgres`, migrated through `V13` from prior sessions, built via `mvnd clean
package`, booted the jar, drove every scenario through the real HTTP API with `curl`,
verified state via direct `psql` queries at every step, not just HTTP status codes):

- **Migration (`V14`)**: confirmed pre-state lacked `ORDER_REJECTED` in the `CHECK`
  constraint, confirmed Flyway applied `V14` cleanly (schema version 14), confirmed the
  `CHECK` now includes it, functionally confirmed via a live `reject` call.
- **Notification creation on every transition**: exercised Standard
  (`createOrder`→accept, →reject, →accept→cancel-by-customer, →accept→cancel-by-professional)
  and SOS (`createSosOrder`→accept→cancel-by-customer) paths against real orders, verified via
  direct DB query against the full §4.2 trigger→recipient mapping. Confirmed the reverse
  recipient never fires. Zero bugs.
- **In-app feed endpoints**: `GET /api/notifications` (`IN_APP`-only, correct ordering,
  correct `unreadCount`), the `unreadOnly=true` filter, `POST /{id}/read` (idempotent —
  verified via DB that `read_at` doesn't change on a second call), `403` on someone else's
  notification, `404` on a nonexistent id, `404` (not `500`) on a malformed id (the fixed
  bug, above), `POST /read-all` (correct count, doesn't touch other users' rows). Zero bugs.
- **Email dispatch job**: all `PENDING` `EMAIL` rows transitioned to `SENT` with `sent_at`
  populated within ~15-20s (consistent with the 20s `fixedDelay`), `[MOCK EMAIL]` log lines
  confirmed with correct recipient/subject/body content. End-of-session state: 22 `EMAIL`
  rows, all `SENT`, zero stuck in `PENDING`/`FAILED`. Pass.
- **Expiry sweep (highest-risk item)**: backdated real `orders.created_at` via direct SQL to
  simulate timeout, waited for the real 60s `@Scheduled` sweep. Verified precisely at both
  boundaries: Standard backdated 16min → `EXPIRED`; Standard backdated 10min → stays
  `PENDING` (not over-eager); SOS backdated 6min → `EXPIRED`; SOS backdated 3min → stays
  `PENDING`. Full side-effect verification on both expired orders: `orders.order_status =
  'EXPIRED'` + `updated_at` bumped, `issues.status = 'EXPIRED'`, Standard slot released
  (`availability_slots.is_available` back to `true`), SOS's `slot_id` correctly `NULL` (safe
  no-op), `ORDER_EXPIRED` notification for the customer only (both channels) with **zero**
  such rows for the professional (customer-only design holds). "Lost the race" case verified:
  an order backdated past timeout but accepted via the real API *before* the sweep ran stayed
  `CONFIRMED`, not flipped, no exception. Also incidentally observed the sweep correctly
  auto-fire on genuinely stale `PENDING` orders left over from the MS4 QA session at app
  startup. Zero bugs — the strongest-verified part of the milestone.
- **Full regression pass (MS0-4)**: `/actuator/health`, `/api/users/me`, issue creation +
  mock AI classification, image upload round-trip, Standard professional/slot listing
  (soft-delete exclusion still correct), SOS availability toggle/listing, error-code taxonomy
  spot-checks (`409 ORDER_NOT_PENDING`, `409 ISSUE_NOT_BOOKABLE`) all still correct. Zero
  regressions. This regression pass also reconfirmed the `EXPIRED`-issue-can't-be-rebooked
  gap live (not a new finding — restates the already-flagged `data-model.md` §4 item; see
  Known gaps below).
- **Final verdict: full sign-off, zero known open bugs.** The one bug found (the
  malformed-id `500`-vs-`404` issue) was caught and fixed *before* this formal QA pass ran,
  during the lead's own review of the coding agent's output; QA then live-verified the fix,
  so QA's own pass itself found zero new defects.

**Known gaps, not blockers** (carried forward and updated through Milestone 7's closing
documentation pass, 2026-08-15):

- **No email retry/backoff for `FAILED` rows — confirmed still deferred, not Milestone 7
  scope.** Re-verified this pass that the gap is not load-bearing for any
  currently-existing behavior: `EmailDispatchJob` only ever runs against
  `LoggingEmailSender`, which performs no real I/O and never throws for transient-network
  reasons, so a `FAILED` row today can only arise from a genuine application bug. Backlog
  item for when a real SMTP/SES provider is chosen (`hardening-plan.md` §4.2).
- **No multi-instance email-dispatch atomic "claim" step — confirmed still deferred, not
  Milestone 7 scope.** Re-verified this pass that this project has never run more than one
  backend instance in any environment. Backlog item for if/when a real multi-instance
  deployment is planned (`hardening-plan.md` §4.3).
- `ON_THE_WAY`/`COMPLETED` notification hooks — **stale note corrected**: these were in
  fact wired in Milestone 6 (`bookings.service.BookingsService.onTheWay`/`.complete`, each
  calling `recordOrderNotification(...)` to the customer, following the exact pattern this
  package established) — see `bookings/README.md`'s Milestone 6 section. Not a gap as of
  Milestone 6 onward; this bullet previously (incorrectly) still described it as pending.
- `auth`'s `EMAIL_VERIFICATION` message type is not retrofitted into this table (deliberate
  scope call, §6 item 6 of the contract doc) — unchanged.
- **The `EXPIRED`-issue-cannot-be-rebooked gap** (`docs/architecture/data-model.md` §4,
  `docs/architecture/api-contract-notifications.md` §7) — **resolved, Milestone 7
  (2026-08-15)**: the user has ruled `EXPIRED` stays a final, permanent `issues.status`
  state; no reopen endpoint, no relaxed booking guard. Not this package's endpoint surface
  to have implemented either way (no endpoint in this package touches `issues.status`).
