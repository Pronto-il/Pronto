# Pronto — REST API Contract: Milestone 5 (Notifications & Real-Time Status)

Status: **design pass, ready for `pronto-coding`** — written 2026-08-13, before any Milestone
5 code exists, mirroring exactly how `api-contract-bookings.md` was produced before
Milestone 3/4 coding started. Nothing in this doc has been implemented yet.

Written by `pronto-planning`. Builds on:
- `docs/architecture/overview.md` §3.3 (short-polling decision), §3.6 (notifications
  channels), §4 (`notifications` package row).
- `docs/architecture/data-model.md` §2.9 (`orders`), §2.10 (`notifications`), §3 item 8
  (the full `EXPIRED` semantics writeup and its "Implementation dependency" paragraph,
  which assigns sweep-job ownership to this milestone).
- `docs/architecture/api-contract-bookings.md` §3.4 ("Milestone 5's forward dependency"),
  §3.6 (`issues.status` transition table's `EXPIRED` row), §4 (both flow summaries), §7
  ("no notification is sent..." bullet).
- `docs/architecture/api-contract.md` §1 (error envelope), §3.1/§3.3 (JWT/role-gating
  mechanism, the `EmailSender` decision this doc extends).
- The already-applied `V1`–`V13` migrations, plus the real, already-built `bookings`/
  `auth.email`/`common` code read directly for this pass (`BookingsService.java`,
  `OrderRepository.java`, `IssueRepository.java`, `EmailSender.java`,
  `LoggingEmailSender.java`, `application.yml`, `ErrorCode.java`,
  `GlobalExceptionHandler.java`) — not assumed from prose.

Scope: the `notifications` package (in-app feed endpoints, email dispatch, the
`PENDING`-order expiry sweep) plus the specific, precise hook points this requires adding
to the already-shipped `bookings` package (`BookingsService`, `OrderRepository`,
`IssueRepository`). Does **not** redesign `GET /api/bookings/orders/{orderId}` (the
order-tracking polling endpoint already exists, MS3) and does **not** build the
`ON_THE_WAY`/`COMPLETED` progression endpoints (still Milestone 6's job, per
`api-contract-bookings.md` §6 item 4 — this doc only decides whether their notification
*hooks* should be pre-wired, §4.6).

This doc is a **precise contract spec** (request/response JSON shapes, status codes, error
codes, exact method signatures for the cross-package hooks) — writing the
entities/repositories/services/controllers is `pronto-coding`'s job.

---

## 0. Conventions (reused verbatim from `api-contract.md` §0 / `api-contract-bookings.md` §0)

| Convention | Choice |
|---|---|
| Base path | `/api/notifications/*` (`notifications` package). |
| Request/response bodies | JSON, `camelCase`. |
| Auth header | `Authorization: Bearer <jwt>` — every endpoint in this doc requires auth. |
| Timestamps in JSON | ISO-8601 / RFC 3339 with offset. |
| Language of error messages | English; Hebrew presentation is the frontend's job. |
| Path-referenced vs. body-referenced ids | `{id}` in `POST /api/notifications/{id}/read` names *the resource the URL is about* → **`404 NOT_FOUND`** if it doesn't resolve (same convention as `api-contract-bookings.md` §0's table). |

### 0.1 Role gating — no interceptor registration needed this milestone

Every endpoint below is **either role** (`CUSTOMER` or `PROFESSIONAL`), self-scoped by the
caller's own `user_id` — a customer and a professional both have a notification feed, and
there is no route in this doc that's restricted to one role. Exactly the same reasoning
`api-contract-bookings.md` §0.1 already established for `cancel`/`GET .../{orderId}`/`GET
.../me` applies uniformly to *all* of this package's routes: `auth.config.SecurityConfig`'s
blanket `.anyRequest().authenticated()` already proves the caller holds a valid JWT for one
of the two v1.0 roles, and the real authorization (does this notification belong to this
caller?) happens in the service layer once the row is loaded. **Consequence: no
`notifications.config.NotificationsWebConfig` class is needed at all this milestone** — not
an oversight, a deliberate minimalism call (don't add an empty/no-op `WebMvcConfigurer` just
for structural symmetry with other packages that actually need one).

---

## 1. Prerequisite migration — `V14`, closing the `ORDER_REJECTED` schema gap

**Confirmed requirement, not a recommendation** — same category of pre-existing gap `V11`/
`V13` fixed ahead of/during their own milestones. `V9__create_notifications.sql` (already
applied) only allows `('ORDER_CREATED', 'ORDER_CONFIRMED', 'ORDER_ON_THE_WAY',
'ORDER_COMPLETED', 'ORDER_CANCELLED', 'ORDER_EXPIRED', 'EMAIL_VERIFICATION')` — missing
`'ORDER_REJECTED'`, contradicting `data-model.md` §2.10's explicit statement that
`ORDER_REJECTED` was "added alongside the new `REJECTED` order status ... to keep this 1:1
mapping intact." `V9` must **not** be edited in place. Next free Flyway version (`V1`–`V13`
already exist, confirmed by listing `backend/src/main/resources/db/migration/`):

```sql
-- V14__alter_notifications_message_type_add_rejected.sql
--
-- Adds 'ORDER_REJECTED' to notifications.message_type, closing the schema gap flagged in
-- docs/architecture/data-model.md §2.10 ("ORDER_REJECTED added alongside the new REJECTED
-- order status ... to keep this 1:1 mapping intact") that V9__create_notifications.sql
-- never actually implemented. Same category of pre-existing gap V11/V13 fixed ahead of/
-- during their own milestones. V9 must not be edited in place.

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_message_type;

ALTER TABLE notifications ADD CONSTRAINT ck_notifications_message_type CHECK (message_type IN (
    'ORDER_CREATED', 'ORDER_CONFIRMED', 'ORDER_ON_THE_WAY', 'ORDER_COMPLETED',
    'ORDER_CANCELLED', 'ORDER_REJECTED', 'ORDER_EXPIRED', 'EMAIL_VERIFICATION'
));
```

**No other schema change is required.** `related_order_id`, `channel`, `delivery_status`,
`read_at`, `sent_at` already exist and already support every design decision in this doc
(§4 below) — tried hard not to need a new column, per the task brief, and didn't.

---

## 2. Error response envelope (reused verbatim)

Same envelope shape as `api-contract.md` §1 / `api-contract-bookings.md` §2. **No new
`ErrorCode` values are needed by this milestone** — audited against every behavior branch
below: "notification not found" reuses `NOT_FOUND`; "not your notification" reuses
`FORBIDDEN`; there is no new validation/conflict case in this package's endpoints. Stated
explicitly since the task brief asked this to be confirmed, not assumed.

---

## 3. Endpoints

### 3.1 `GET /api/notifications?unreadOnly={bool}`

Auth required: **yes**. Role: **either** (§0.1).

The in-app notification feed / bell. Returns **`IN_APP`-channel rows only** — `EMAIL`-channel
rows are an internal dispatch-pipeline concern (§4.3) never surfaced through this endpoint;
returning them here would show the user each event twice (once per channel).

**Query params**: `unreadOnly` (optional boolean, default `false`). When `true`, filters to
`read_at IS NULL`.

**Behavior:**
1. Resolve caller.
2. Query `notifications WHERE user_id = caller.id AND channel = 'IN_APP' [AND read_at IS
   NULL]`, ordered by `created_at DESC` (matches `idx_notifications_user_created`).
3. Separately compute `unreadCount = COUNT(*) WHERE user_id = caller.id AND channel =
   'IN_APP' AND read_at IS NULL` — **always** computed regardless of the `unreadOnly` filter,
   so the bell badge count stays correct even when the list itself is unfiltered.
4. Return both.

**No pagination this milestone** — consistent with `api-contract-bookings.md`'s established
"no pagination this milestone" convention (§2.9/§2.11 there); flag as an Milestone 7
hardening candidate if per-user notification volume ever grows large enough to matter (§6
there already flags the same thing for orders/slots).

**Response `200`:**
```json
{
  "unreadCount": 2,
  "notifications": [
    {
      "id": 501,
      "messageType": "ORDER_CONFIRMED",
      "relatedOrderId": 900,
      "readAt": null,
      "createdAt": "2026-08-13T12:41:30Z"
    },
    {
      "id": 498,
      "messageType": "ORDER_CREATED",
      "relatedOrderId": 900,
      "readAt": "2026-08-13T12:35:00Z",
      "createdAt": "2026-08-13T12:34:00Z"
    }
  ]
}
```

`channel`/`deliveryStatus` are **not** included in the response — internal dispatch-pipeline
fields with no meaning to an in-app viewer (every row returned here is, by construction,
`channel = 'IN_APP'`; `deliveryStatus` is set to `SENT` at insert time for every `IN_APP` row,
§4.3, so it carries no information worth exposing).

**Status codes**: `200` success · `401 UNAUTHORIZED`.

---

### 3.2 `POST /api/notifications/{id}/read`

Auth required: **yes**. Role: **either** (§0.1).

Marks one notification read. **Idempotent** — calling it on an already-read notification is
not an error, it's just a no-op success.

**Behavior:**
1. Resolve caller.
2. Load notification by `{id}` path variable → `404 NOT_FOUND` if missing (path-referenced
   id, §0).
3. `notification.userId != caller.id` → `403 FORBIDDEN` (the case the task brief asked to be
   confirmed — "marking someone else's notification as read" — reuses the existing
   `FORBIDDEN` code, no new one needed, §2).
4. If `read_at IS NULL`, set `read_at = now()`. If already non-null, leave unchanged (no
   second timestamp overwrite — the *first* read time is the meaningful one).
5. Return `200` with the updated row.

**Response `200`:**
```json
{
  "id": 501,
  "messageType": "ORDER_CONFIRMED",
  "relatedOrderId": 900,
  "readAt": "2026-08-13T12:50:00Z",
  "createdAt": "2026-08-13T12:41:30Z"
}
```

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND`.

---

### 3.3 `POST /api/notifications/read-all`

Auth required: **yes**. Role: **either** (§0.1).

Convenience "clear the bell" action. **Recommendation, built here as a judgment call** — not
explicitly requested by any source document, but cheap (a single guarded `UPDATE`) and a
near-universal pattern for this exact UI shape; flagged as a judgment call rather than
silently assumed necessary.

**Behavior:**
1. Resolve caller.
2. `UPDATE notifications SET read_at = now() WHERE user_id = caller.id AND channel = 'IN_APP'
   AND read_at IS NULL`.
3. Return the affected-row count.

**Response `200`:**
```json
{ "updatedCount": 2 }
```

**Status codes**: `200` success · `401 UNAUTHORIZED`.

---

## 4. Cross-cutting mechanisms

### 4.1 `NotificationService` — the `bookings` → `notifications` call boundary

**Dependency direction, matching `notifications/README.md`'s existing "Written to by
`bookings`... / Will trigger `notifications`..." statement**: `bookings` depends on and calls
into `notifications`, not the reverse (with one deliberate, flagged exception for the expiry
sweep — §4.5). To avoid a circular *compile* dependency (`notifications` must not need to
import `bookings.entity.Order`), the interface uses primitive/enum parameters only:

```java
package com.pronto.notifications.service;

public interface NotificationService {
    /**
     * Records a notification for one order-lifecycle event, for one recipient. Internally
     * creates two notifications rows (IN_APP + EMAIL) — see §4.3. Called by
     * bookings.service.BookingsService after every successful order-status transition.
     */
    void recordOrderNotification(Long orderId, Long recipientUserId, NotificationMessageType messageType);
}
```

`NotificationMessageType` is a new `notifications`-owned enum mirroring the `message_type`
`CHECK` values 1:1 (`ORDER_CREATED, ORDER_CONFIRMED, ORDER_ON_THE_WAY, ORDER_COMPLETED,
ORDER_CANCELLED, ORDER_REJECTED, ORDER_EXPIRED, EMAIL_VERIFICATION`) — `bookings` imports
this enum (one-directional `bookings → notifications` dependency, same direction as the
interface above), the same way `issues` already imports `ai.client.AiClassificationClient`
directly (no `common`-package indirection exists in this codebase's convention for
cross-domain-package service calls, so none is introduced here either).

**`BookingsService`'s constructor gains a new dependency**, `NotificationService
notificationService` — a real, required code change to the already-shipped class, not
optional. Injected exactly like every other repository/service it already takes.

### 4.2 Notification-trigger-to-recipient mapping (every `bookings` transition, MS3/MS4 + this milestone's new one)

| Trigger (existing method, unless noted) | `messageType` | Recipient | Reasoning |
|---|---|---|---|
| `createOrder` (§2.4) / `createSosOrder` (§2.13) — `orders` row inserted, `PENDING` | `ORDER_CREATED` | **Professional** (`professional.getUserId()`, already loaded in-method) | The professional needs to learn a request landed; the customer already knows (they just submitted it) — no customer-facing "created" notification. |
| `accept` (§2.5) — `PENDING → CONFIRMED` | `ORDER_CONFIRMED` | **Customer** (`order.getCustomerId()`) | Customer needs to learn their request was accepted; the professional doesn't need telling about their own action. |
| `reject` (§2.6) — `PENDING → REJECTED` | `ORDER_REJECTED` | **Customer** | Customer needs to know to return to the professional list (§4 of `api-contract-bookings.md`'s reject→return-to-list branch); professional doesn't need telling about their own action. |
| `cancel` (§2.7) — `→ CANCELLED` | `ORDER_CANCELLED` | **The *other* party** — if `actor == CUSTOMER`, notify the professional (resolve via `professionalRepository.findById(order.getProfessionalId()).getUserId()`); if `actor == PROFESSIONAL`, notify the customer (`order.getCustomerId()`) | The party who backed out doesn't need notifying about their own action; the other party needs to know the order is off. |
| **New this milestone**: `expireIfPending` (§4.5) — `PENDING → EXPIRED` | `ORDER_EXPIRED` | **Customer** | Judgment call, symmetric with `reject`'s customer-only choice — the customer needs to know their request timed out and the issue needs rebooking; the professional took no action here (that's the whole point of an expiry) so there's nothing to tell them, mirroring `reject`'s reasoning even though the *cause* differs. |
| `ON_THE_WAY`/`COMPLETED` progression | `ORDER_ON_THE_WAY` / `ORDER_COMPLETED` | *(not wired)* | **Decided: no hook added this milestone** — see §4.6 for the full reasoning. |
| Registration verification code (`auth.service.AuthService`) | `EMAIL_VERIFICATION` | *(not wired)* | **Decided: `auth` is not retrofitted to write a `notifications` row this milestone** — see §6 item 5. |

### 4.3 Two rows per event: `IN_APP` (delivered immediately) + `EMAIL` (dispatched async)

`NotificationServiceImpl.recordOrderNotification` inserts **two** `notifications` rows per
call, matching what the already-provisioned partial index
(`idx_notifications_channel_status ON (channel, delivery_status) WHERE delivery_status =
'PENDING'`, `V9`) was clearly built for — an "email-dispatch worker's what-still-needs-
sending query," per `data-model.md` §2.10's own index comment:

| Row | `channel` | `delivery_status` at insert | `sent_at` at insert |
|---|---|---|---|
| In-app | `IN_APP` | `SENT` (no separate dispatch step — the row itself being queryable *is* delivery, §3.1) | `now()` |
| Email | `EMAIL` | `PENDING` | `NULL` (set later by the dispatch job, §4.4) |

Both inserts happen **inside the same `@Transactional` boundary as the order transition
that triggered them** (i.e., inside `BookingsService`'s existing `@Transactional` methods,
not a separate transaction) — deliberately simple: no outbox pattern, no event bus. This is
safe because the insert itself has no external I/O (email dispatch is deferred to the async
job, §4.4) — a plain DB insert failing for reasons unrelated to the order transition is
low-probability, and keeping both writes atomic is simpler than the alternative for a
two-person MVP team. Flagged as a deliberate simplicity choice, not an oversight.

### 4.4 Email dispatch — extends `auth.email.EmailSender`, config flag `pronto.email.mode`

**Decision (option (a) of the three the task brief posed): extend the existing
`auth.email.EmailSender` interface**, not a new `notifications`-owned abstraction. Reasoning,
checked directly against the real MS1 code: `LoggingEmailSender`'s own Javadoc already says
"the same `EmailSender` interface should be swapped for a real implementation" (quoting
`api-contract.md` §3.3, which this doc's brief also points at) — that sentence only makes
sense if the *same* interface is reused, not superseded by a second one. Introducing a
second, `notifications`-owned interface for order-status email would fork the abstraction
MS1 explicitly built forward-compatibility for, with no benefit (verification-code email and
order-status email are the same underlying concern — "send this account holder an email" —
just different content).

```java
package com.pronto.auth.email;

public interface EmailSender {
    void sendVerificationCode(String toEmail, String code);       // unchanged, MS1
    void sendOrderStatusEmail(String toEmail, String subject, String bodyText);  // new, MS5
}
```

`LoggingEmailSender` implements the new method the same way it already implements the first
— logs at `INFO`, sends nothing. `notifications` package code depends on
`auth.email.EmailSender` (one more `notifications → auth` compile dependency, alongside the
pre-existing `notifications → users` one implied by the FK) — no new architectural pattern,
`issues → ai` already established that domain packages call each other's interfaces directly.

**Config flag — `pronto.email.mode`, default `log`.** Mirrors `pronto.ai.mode`/
`pronto.storage.mode` exactly:

```yaml
pronto:
  email:
    mode: ${EMAIL_MODE:log}   # log | smtp (smtp not built this milestone, see below)
```

**Recommendation, explicit call per the task brief's instruction to decide rather than
silently default**: **do not** build a second, compiles-but-untested `SmtpEmailSender` (or
`SesEmailSender`) class this milestone, unlike `OpenAiClassificationClient`/`S3StorageClient`
in Milestone 2. The reasoning is *not* the same as MS2's "no credentials yet" — MS2's
provider was already named by the poster (OpenAI, S3), only credentials were missing. Here,
no source document names an email provider at all (SES vs. SMTP vs. a third-party API is
genuinely undecided) — writing an untested class against an unspecified/unpicked provider
has no clear target to compile against faithfully and would likely need rework once a real
provider is chosen anyway. `pronto.email.mode` is still introduced now (cheap, forward-
compatible, matches the established naming pattern) with **`log` as the only implemented
value** — `LoggingEmailSender` stays the sole `@Component`, no `@ConditionalOnProperty`
branching needed yet (there's nothing to branch to). When a provider is chosen, add the real
implementation behind `@ConditionalOnProperty(prefix = "pronto.email", name = "mode",
havingValue = "smtp")`, exactly mirroring `pronto.ai.mode`'s `mock`/`openai` split — no
redesign implied, just the same pattern applied once there's something to apply it to.

**The scheduled dispatch job** — `notifications.scheduler.EmailDispatchJob`:

```java
@Scheduled(fixedDelay = 20_000)  // 20s
public void dispatchPendingEmails() {
    // SELECT ... FROM notifications WHERE channel = 'EMAIL' AND delivery_status = 'PENDING'
    // ORDER BY created_at ASC LIMIT 50   (uses idx_notifications_channel_status)
    // for each: resolve recipient email via users.repository.UserRepository, build a
    // generic subject/body from (messageType, relatedOrderId) — see §7 for why exact copy
    // is explicitly not decided here — call emailSender.sendOrderStatusEmail(...), then
    // mark delivery_status = SENT (sent_at = now()) on success or FAILED on any exception
    // (logged at WARN, not rethrown — one failed row must not block the rest of the batch).
}
```

**Interval choice, 20s**: no source document sets a target for email latency (unlike the
PRD's ~1s target for *in-app* status observability, which is about the polling client
observing an already-changed `orders.updated_at`/`notifications` row — not about email at
all). 20s is a judgment call, shorter than the 60s expiry-sweep interval (§4.5) simply
because per-row dispatch work is cheap and there's no reason to make users wait longer than
necessary for the email channel specifically. Trivially tunable later, not a hard
requirement.

**Multi-instance race, flagged not silently ignored**: this job has no per-row atomic
"claim" step before sending (unlike every `orders`/`issues` transition in
`api-contract-bookings.md` §3.2's guarded-`UPDATE` pattern) — two backend instances polling
concurrently could both pick up and send the same `PENDING` email row before either marks it
`SENT`. **Accepted MVP gap**: `overview.md` §6 already states AWS's managed-container/
multi-instance setup is "not yet confirmed as needed" for v1.0 — this app is not currently
expected to run more than one instance. If/when it does, this job needs an atomic claim step
(e.g. an interim `delivery_status` value, which would need a `V15` `CHECK` addition) before
it's safe under horizontal scaling. Not built now — flagged, not silently risked.

### 4.5 The expiry sweep — ownership split, mechanism, interval

**Ownership split, applied exactly as `data-model.md` §3 item 8's "Implementation
dependency" paragraph and `api-contract-bookings.md` §3.4's "Milestone 5's forward
dependency" paragraph both already dictate**: `bookings` owns the domain rule and the actual
state transition (new methods added to the already-shipped `BookingsService`,
`OrderRepository`, `IssueRepository`); `notifications` owns building and running the
`@Scheduled` sweep that *invokes* those methods, plus the notification-creation call that
happens as a normal consequence of any successful transition (§4.1/§4.2 above — the sweep is
not a special case there, it just triggers a transition like any other caller).

**Timeout constants — DECIDED (per the task brief's "Decided facts"), hardcoded as
application-level constants, no migration**: `Duration.ofMinutes(15)` for `STANDARD`
issues, `Duration.ofMinutes(5)` for `SOS` issues. Live in `BookingsService` (the package that
owns the domain rule):

```java
private static final Duration STANDARD_PENDING_TIMEOUT = Duration.ofMinutes(15);
private static final Duration SOS_PENDING_TIMEOUT = Duration.ofMinutes(5);
```

**New `bookings` repository/service methods** (real code changes to already-shipped
classes, specified precisely so `pronto-coding` isn't guessing):

```java
// bookings.repository.OrderRepository — new methods, alongside the existing three
// @Modifying atomic transitions (acceptIfPending/rejectIfPending/cancelIfStatus).

/** Mirrors rejectIfPending exactly, target status EXPIRED instead of REJECTED. */
@Modifying(clearAutomatically = true)
@Query("UPDATE Order o SET o.orderStatus = com.pronto.bookings.entity.OrderStatus.EXPIRED, "
        + "o.updatedAt = :now WHERE o.id = :orderId AND o.orderStatus = com.pronto.bookings.entity.OrderStatus.PENDING")
int expireIfPending(@Param("orderId") Long orderId, @Param("now") Instant now);

/**
 * Candidate PENDING orders past their per-urgency-type timeout, for the sweep. Cross-entity
 * comma-join JPQL, same style as bookings.repository.ProfessionalListingRepository's
 * existing Professional/User/SosAvailability joins — not a new query convention.
 */
@Query("SELECT o.id FROM Order o, Issue i WHERE o.issueId = i.id "
        + "AND o.orderStatus = com.pronto.bookings.entity.OrderStatus.PENDING "
        + "AND ((i.urgencyType = com.pronto.issues.entity.IssueUrgencyType.STANDARD AND o.createdAt < :standardCutoff) "
        + "OR (i.urgencyType = com.pronto.issues.entity.IssueUrgencyType.SOS AND o.createdAt < :sosCutoff))")
List<Long> findPendingExpiryCandidateIds(@Param("standardCutoff") Instant standardCutoff,
                                          @Param("sosCutoff") Instant sosCutoff);
```

```java
// issues.repository.IssueRepository — one new method, alongside bookIfOpen/revertToOpen.

/** Unconditional-except-for-the-guard, same shape as bookIfOpen; BOOKED is the only state
  * an order's issue can be in while that order is still PENDING (§3.3's single-active-order
  * invariant guarantees this — see the reasoning in the body text below). */
@Modifying(clearAutomatically = true)
@Query("UPDATE Issue i SET i.status = com.pronto.issues.entity.IssueStatus.EXPIRED, i.updatedAt = :now "
        + "WHERE i.id = :issueId AND i.status = com.pronto.issues.entity.IssueStatus.BOOKED")
int expireIfBooked(@Param("issueId") Long issueId, @Param("now") Instant now);
```

```java
// bookings.service.BookingsService — two new public methods.

@Transactional(readOnly = true)
public List<Long> findExpiredOrderCandidateIds() {
    Instant now = Instant.now();
    return orderRepository.findPendingExpiryCandidateIds(
            now.minus(STANDARD_PENDING_TIMEOUT), now.minus(SOS_PENDING_TIMEOUT));
}

/** Mirrors reject()'s shape exactly, but is called by a background job, not an HTTP
  * request — no ApiException is thrown for "lost the race" (0 affected rows just means
  * another caller already moved the order out of PENDING; treated as a normal, silent
  * outcome, not an error, since there is no HTTP caller to report a 409 to). */
@Transactional
public Optional<OrderResponse> expireIfPending(Long orderId) {
    Instant now = Instant.now();
    int affected = orderRepository.expireIfPending(orderId, now);
    if (affected == 0) {
        return Optional.empty();
    }
    Order order = loadOrder(orderId);
    issueRepository.expireIfBooked(order.getIssueId(), now);      // §3.3 guarantees this always affects 1 row when reached
    availabilitySlotRepository.releaseSlot(order.getSlotId(), now); // same §3.4 mechanism reject/cancel already use, safe no-op for SOS
    notificationService.recordOrderNotification(orderId, order.getCustomerId(), NotificationMessageType.ORDER_EXPIRED);
    return Optional.of(toOrderResponse(order));
}
```

This satisfies `api-contract-bookings.md` §3.4's explicit instruction: the sweep "must
release the slot using the *same* mechanism as reject/cancel... ideally by calling the same
domain-service method reject/cancel call internally, not reimplementing the release logic a
third time" — `expireIfPending` reuses the exact same `availabilitySlotRepository
.releaseSlot(...)` call `reject`/`cancel` already use (via the existing private
`releaseSlotAndReopenIssue` helper's slot half — `expireIfPending` doesn't call that helper
directly because it needs `EXPIRED`, not `OPEN`, on the issue side, so it inlines the slot
release and calls the new `expireIfBooked` instead of `revertToOpen`).

**Why `issues.status` becomes `EXPIRED`, unconditionally, not `OPEN`** — resolving an
apparent tension in `data-model.md` §3 item 8's wording ("set `issues.status = 'EXPIRED'`
unless/until the customer rebooks"): given the single-active-order-per-issue invariant
(`api-contract-bookings.md` §3.3), an issue can only be `BOOKED` while its one active order is
`PENDING`/`CONFIRMED`/`ON_THE_WAY` — nothing else can have created a "replacement order" for
this issue while this specific order was still `PENDING` (the very state the sweep's guarded
`WHERE order_status = 'PENDING'` requires to have found it in). So at the exact moment
`expireIfPending`'s guarded transition succeeds, "the customer has not created a replacement
order" is **always already true** — there's no runtime branch to write; the guarded `UPDATE`
itself is the resolution of that clause (if a concurrent `reject`/`cancel`/`accept` beat the
sweep to this order, `expireIfPending`'s own guard returns `0` and does nothing further,
exactly mirroring the "lost the race" pattern `reject`/`cancel` already use). See §6 item 3
below for the *separate*, genuinely open question this raises: whether a customer can
actually rebook an `EXPIRED` issue afterward given the existing endpoints' `status == 'OPEN'`
requirement.

**Scheduled trigger — `notifications.scheduler.OrderExpirySweepJob`:**

```java
@Component
public class OrderExpirySweepJob {
    private final BookingsService bookingsService;   // notifications -> bookings, see the note below

    @Scheduled(fixedDelay = 60_000)  // 60s
    public void sweep() {
        for (Long orderId : bookingsService.findExpiredOrderCandidateIds()) {
            bookingsService.expireIfPending(orderId);
        }
    }
}
```

`@EnableScheduling` lives on a small `notifications.config.SchedulingConfig
(@Configuration @EnableScheduling)` class, rather than added to `ProntoApplication` directly
— keeps the "this package needs scheduling" fact localized to the package that needs it.

**Interval choice, 60s, and why this does *not* contradict the PRD's ~1s status-update
target** — stated explicitly since it's exactly the kind of thing that could look like a
contradiction if not addressed: the PRD's ~1s target (`overview.md` §3.3) is about how fast
an **already-changed** status reaches a polling client — i.e., the gap between `orders
.updated_at` changing and a 3–5s-interval polling client next observing it. It has nothing to
do with how fast the backend *notices* that a `PENDING` order should expire in the first
place — that's a detection latency, not a propagation latency, and no source document sets a
target for it. A 60s sweep interval means, worst case, an order that hit its 15-/5-minute
timeout waits up to another 60s before the sweep transitions it — negligible relative to the
15-/5-minute timeout itself (a <7% worst-case overshoot on the 5-minute SOS case, smaller
still for Standard), and once the sweep *does* transition it, the existing 3–5s polling
mechanism picks up the change with the PRD's normal latency characteristics, unaffected by
this job's own cadence.

**Deliberate, flagged package-dependency exception**: `OrderExpirySweepJob` (in
`notifications`) depends on `BookingsService` (in `bookings`) — a `notifications → bookings`
edge, alongside the pre-existing `bookings → notifications` edge from §4.1
(`NotificationService`). Together these form a **package-level cycle** between `bookings` and
`notifications`. This is **not** a silent architectural slip — it's the direct, unavoidable
consequence of the ownership split `data-model.md` §3 item 8 and `pronto-lead` already
decided ("the notifications-milestone job owns building/running the sweep that invokes it" —
i.e., the trigger must live in `notifications` and must call into `bookings`). It does not
create a Java-level compile cycle in the risky sense (no single class pair mutually imports
each other — `BookingsService` imports `notifications.service.NotificationService`/
`NotificationMessageType`; `OrderExpirySweepJob` imports `BookingsService`; these are two
independent directed edges), and since this is one Spring Boot module (not separate Maven
modules), nothing enforces package boundaries at compile time regardless. Flagged here so it
isn't rediscovered as a surprise later, per the task brief's "don't silently pick an
interpretation" instruction — this is a call being made explicitly, not an oversight.

### 4.6 `ON_THE_WAY`/`COMPLETED` notification hooks — decided: not wired this milestone

**Decision, with reasoning stated rather than silently deferred**: do **not** add
notification-creation calls for `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` this milestone, even as
dead code. Reasoning: unlike the five transitions in §4.2 (all of which have a real,
already-shipped `BookingsService` method to attach a call to), **no method producing these
two statuses exists yet at all** — `api-contract-bookings.md` §3.6 and §6 item 4 both
confirm `ON_THE_WAY`/`COMPLETED` progression is Milestone 6's scope, not built by any
endpoint through MS4. There is no call site to add a hook *to* — writing a
`notificationService.recordOrderNotification(..., ORDER_ON_THE_WAY)` call with no caller
would be genuinely dead code (unreachable, since nothing sets that status), which is exactly
what "no premature abstractions, no infrastructure the current scope doesn't need" warns
against. `NotificationMessageType.ORDER_ON_THE_WAY`/`.ORDER_COMPLETED` **do** already exist
in the enum (mirroring the DB `CHECK`, which already lists them per `V9`) — so Milestone 6
adds exactly one line per new transition method (`notificationService.recordOrderNotification
(orderId, order.getCustomerId(), NotificationMessageType.ORDER_ON_THE_WAY)`, following the
identical pattern §4.1/§4.2 already establish), not a new mechanism.

---

## 5. End-to-end notification-observability flow

Not a new booking flow (those are unchanged, `api-contract-bookings.md` §4) — this is how
the notification feed behaves alongside them, for clarity:

1. Any of the five triggers in §4.2 fires inside an existing (or, for expiry, new)
   `BookingsService` transactional method.
2. `notificationService.recordOrderNotification(...)` inserts one `IN_APP` row
   (`delivery_status = SENT` immediately) and one `EMAIL` row (`delivery_status = PENDING`),
   same transaction, same commit as the order-status change itself.
3. The recipient's client — already polling `GET /api/bookings/orders/{orderId}` (§2.8 of
   the bookings contract) every 3–5s for the order it cares about, per the existing
   short-polling design — can *additionally* poll `GET /api/notifications` (§3.1 above) on
   the same cadence to drive a bell/unread-count UI independent of any specific order screen
   (e.g. a professional not currently looking at one specific order's tracking screen still
   sees their unread count rise when a new request lands).
4. Independently, within 20s (§4.4), `EmailDispatchJob` picks up the `PENDING` `EMAIL` row and
   calls `emailSender.sendOrderStatusEmail(...)`, marking it `SENT`/`FAILED`.
5. For the new expiry case specifically: within 60s of the 15-/5-minute timeout (§4.5),
   `OrderExpirySweepJob` transitions the order to `EXPIRED` and the issue to `EXPIRED`,
   triggering the same §4.2/§4.3 notification-creation path as any other transition — the
   customer's next poll of either `GET /api/bookings/orders/{orderId}` or `GET
   /api/notifications` observes it.

---

## 6. Decisions — resolution record

Per the task brief's instruction not to silently pick an interpretation, every meaningful
call this doc makes is recorded here for traceability.

1. **`V14` migration — DECIDED, built as specified in §1.** Closes the pre-existing
   `ORDER_REJECTED` gap in `V9`, same category as `V11`/`V13`.

2. **Email abstraction — DECIDED: extend `auth.email.EmailSender` (option (a)), not a new
   `notifications`-owned interface.** Justified in §4.4 directly against `LoggingEmailSender`'s
   own forward-looking Javadoc, which only makes sense under this reading.

3. **Real SMTP/SES implementation — DECIDED (recommendation): not built this milestone,
   unlike `OpenAiClassificationClient`/`S3StorageClient`.** The distinguishing factor
   (explained in §4.4) is that MS2's providers were already *named* by the poster with only
   credentials missing, while no source document names an email provider at all — building an
   untested class against an unpicked target isn't a clean parallel to MS2's case. `LoggingEmailSender`
   remains the sole implementation; `pronto.email.mode` is introduced now for forward
   compatibility, with only `log` implemented.

4. **Sweep-job ownership split and mechanism — DECIDED, following the already-decided
   assignment in `data-model.md` §3 item 8 / `api-contract-bookings.md` §3.4 exactly.**
   `bookings` gets two new methods (`findExpiredOrderCandidateIds`, `expireIfPending`) that
   own the query and the transition; `notifications` gets a thin `@Scheduled` orchestrator
   (`OrderExpirySweepJob`) that calls them. The resulting `bookings ↔ notifications`
   package-level cycle is flagged explicitly (§4.5's last paragraph) as a direct consequence
   of that already-decided split, not an unnoticed side effect.

5. **`issues.status → 'EXPIRED'` is unconditional when `expireIfPending` succeeds — DECIDED,
   with the reasoning spelled out in §4.5** (the single-active-order invariant makes
   `data-model.md`'s "unless the customer already rebooked" clause always vacuously true at
   the moment a guarded expiry transition actually succeeds).

6. **`auth`'s verification-code flow is *not* retrofitted to write an `EMAIL_VERIFICATION`
   `notifications` row this milestone — DECIDED.** Reasoning: an in-app notification feed has
   no meaningful use case for an event that occurs *before* the user has a usable session
   (they can't be logged in yet to view their bell) — `EMAIL_VERIFICATION`'s existing,
   already-working `LoggingEmailSender.sendVerificationCode` path is left untouched.
   `EMAIL_VERIFICATION` stays in the `message_type` `CHECK`/`NotificationMessageType` enum for
   schema completeness and any future retrofit, but nothing writes it this milestone —
   explicit scope call, not an oversight.

7. **`ON_THE_WAY`/`COMPLETED` notification hooks — DECIDED: not wired, no dead code added.**
   Full reasoning in §4.6 — there's no calling site to attach to yet (Milestone 6 hasn't built
   the transition endpoints).

8. **`POST /api/notifications/read-all` — DECIDED to build (judgment call, not explicitly
   requested).** Cheap, common UI pattern, single guarded `UPDATE`.

---

## 7. Open items / risks (flagging, not blocking `pronto-coding`)

- **Genuinely open, needs product/UX input — exact notification copy/text (subject/body,
  Hebrew).** No source document specifies wording for any notification. `EmailDispatchJob`
  (§4.4) needs *some* text to hand `LoggingEmailSender`/a future real sender — a minimal,
  generic, English placeholder (e.g. `"Pronto — Order #<id>: status changed to <STATUS>"`) is
  assumed sufficient for this milestone's mock-sender logging, **not** production-ready
  Hebrew copy. Matches the task brief's own example of a flag-don't-decide item.
- **DECIDED (user ruling, 2026-08-15, Milestone 7 closing documentation pass) — an
  `EXPIRED` issue cannot be rebooked, and this is intentional, permanent behavior, not an
  open gap.** `data-model.md` §3 item 8's original text said an expired issue's customer can
  rebook, "in which case the normal `OPEN`/`BOOKED` lifecycle applies to the new order,
  exactly as with any other reject-and-rebook case" — but `api-contract-bookings.md` §2.4
  step 6 / §2.13 step 6 (both already shipped, MS3/MS4) require `issue.status == 'OPEN'` to
  create a new order, so `EXPIRED` fails that check with `409 ISSUE_NOT_BOOKABLE`,
  identically to any other terminal issue state, and no endpoint anywhere reopens an
  `EXPIRED` issue back to `OPEN`. **Resolved**: the user has ruled `EXPIRED` stays a final,
  terminal state permanently — no reopen endpoint, no relaxed booking guard on
  `createOrder`/`createSosOrder`, ever. The intended, permanent path for a customer who
  wants service again is to create an entirely new `issues` row describing the same problem
  (`POST /api/issues`), accepting the loss of continuity with the original issue as part of
  the design, not a workaround pending a better answer. No code change results — the
  existing `409 ISSUE_NOT_BOOKABLE` behavior was already correct and already tested; only
  the framing changes here, from "open gap" to "intentional, permanent design." See
  `data-model.md` §4 and `hardening-plan.md` §4.1 for the same resolution recorded from
  those docs' perspectives.
- **No retry/backoff for `FAILED` email rows — CONFIRMED DEFERRED, not Milestone 7 scope.**
  `EmailDispatchJob` marks a row `FAILED` on any exception and moves on — nothing ever
  retries it. Re-verified during Milestone 7's closing pass that this is not load-bearing
  for any currently-existing behavior: `EmailDispatchJob` only ever runs against
  `LoggingEmailSender`, which performs no real I/O and never throws for transient-network
  reasons, so a `FAILED` row today can only arise from a genuine application bug. **Backlog
  entry**: revisit when a real SMTP/SES email provider is chosen and implemented, not
  before. See `hardening-plan.md` §4.2.
- **Multi-instance email-dispatch race — CONFIRMED DEFERRED, not Milestone 7 scope.** See
  §4.4's own flagged paragraph; not repeated here, just cross-referenced. Re-verified during
  Milestone 7's closing pass that this project has never run more than one backend instance
  in any environment, so this gap is not load-bearing for any currently-existing behavior.
  **Backlog entry**: revisit if/when a real multi-instance deployment is actually planned.
  See `hardening-plan.md` §4.3.
- **Unread-badge UI behavior (when to show it, animation, etc.) is explicitly frontend's
  concern**, not addressed by this backend contract doc, per the task brief.
- **No rate limiting on any endpoint in this doc** — low risk (`GET /api/notifications` is a
  single indexed read; the `read`/`read-all` writes are single-row/small-batch guarded
  updates), noted only for completeness, matching the same low-priority treatment
  `api-contract-bookings.md` §7 gives `PUT /api/availability/sos-availability`.
- **SMS/push notification channels remain out of scope** — not requested by any source
  document (`overview.md` §2's already-settled decision), restated here only so it isn't
  assumed this milestone silently reconsidered it.

---

## Addendum — `relatedIssueId` (Pronto SOS final-readiness pass)

`NotificationResponse` carries a third subject hint alongside `relatedOrderId` and
`relatedSosRequestId`:

```jsonc
{
  "id": 1,
  "messageType": "SOS_CANDIDATES_READY",
  "relatedOrderId": null,
  "relatedSosRequestId": 77,
  "relatedIssueId": 42,      // <- new
  "readAt": null,
  "createdAt": "..."
}
```

**Derived, never stored.** There is no `related_issue_id` column and no migration:
`NotificationServiceImpl` resolves it from `relatedSosRequestId` at response-assembly time, in one
batched lookup for the whole (unpaginated) feed.

**Why it exists.** `relatedSosRequestId` is the right subject to *store* — the row is about an SOS
attempt, and the column is FK-constrained to `sos_requests`. But it was not enough to *navigate*
with: the customer's live SOS screen is `/issues/{issueId}/sos-booking`, keyed by the problem rather
than by the attempt (one issue accumulates many attempts, and the customer should land on where
their problem stands now). So every customer-facing SOS row in the bell was a dead end. Professional
rows are unaffected — their destination is `/pro/sos`, which needs no id.

**Nullability.** `null` on every order row, and `null` on an SOS row whose request no longer
resolves. Clients render that as "no deep link", never as a guess — a dead end beats a wrong link.

**Package boundary.** `sos` already depends on `notifications`, so `notifications` declares the port
(`notifications.service.SosRequestIssueResolver`) and `sos` implements it
(`sos.service.SosRequestIssueLookup`). Nothing depends on `sos`. The resolver failing degrades the
feed to "no deep links" rather than failing the request.
