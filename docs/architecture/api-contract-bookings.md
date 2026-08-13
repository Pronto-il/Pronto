# Pronto — REST API Contract: Milestone 3 (Standard Booking Flow)

Status: **FINALIZED — ready for `pronto-coding`, no open sign-off items remain for
Milestone 3.** All four decisions raised in the original draft's §6 have been resolved by
`pronto-lead` (2026-08-13): `orders.slot_id` is approved as a new column (`V12`, decided —
see §1), a minimal `availability` slice is approved for this milestone (option (a) — see
§2.10/§2.11 for the full endpoint contract), the professional-viewing-images question
stays explicitly out of Milestone 3's scope (tracked as an open item, §7, not designed or
built here), and the `ON_THE_WAY`/`COMPLETED`-progression-belongs-to-Milestone-6 reading is
confirmed correct. Every place below that previously described a fallback/conditional
design now states the decided design directly — see §6 for the resolution record.

Written by `pronto-planning`. Builds on:
- `docs/architecture/data-model.md` §2.5 (`availability_slots`), §2.7 (`issues`), §2.9
  (`orders`), §3 items 5/8/9/10 (SOS-vs-Standard availability split, `issues.status`
  lifecycle, nullable `booked_end`, the `REJECTED` decision) — status-transition semantics
  below implement these exactly, not re-litigated.
- `docs/architecture/api-contract.md` (Milestone 1 — JWT/error-envelope/role-gating
  mechanism, reused verbatim) and `docs/architecture/api-contract-issues.md` (Milestone
  2 — same conventions, plus the `RoleRequiredInterceptor` fix noted in §3.1 below).
- The already-applied `V1`–`V10` migrations. **`V8__create_orders.sql` is not edited in
  place** — see §1 for the two new migrations this doc requires: `V11` (adds `REJECTED`
  to `order_status`, a pre-existing gap fix, decided independently of this doc) and `V12`
  (adds `orders.slot_id`, new for this milestone, approved 2026-08-13). §1 also states the
  required apply order and confirms there is no ordering dependency between them beyond
  that convention.

Scope: the `bookings` package, **Standard path only** (SOS is Milestone 4 — nothing below
implements SOS-specific behavior, but §3.7/§4 call out exactly where the design already
generalizes to SOS and where M4 will need net-new endpoints); plus a deliberately narrow
slice of the `availability` package (two endpoints only — slot creation + self-listing,
§2.10/§2.11 — not the Milestone 6 dashboard). Also specifies one small, necessary addition
to the `issues` package (`GET /api/issues/{id}`, §2.1) — reasoning in that section.

This doc is a **precise contract spec** (request/response JSON shapes, status codes, error
codes, field-level validation), not literal Java code — writing the controllers/services is
`pronto-coding`'s job.

---

## 0. Conventions (reused verbatim from `api-contract.md` §0 / `api-contract-issues.md` §0)

| Convention | Choice |
|---|---|
| Base paths | `/api/issues/{id}` (new, `issues` package) for issue lookup; `/api/bookings/*` (`bookings` package) for the booking flow; `/api/availability/*` (`availability` package, new this milestone, §2.10/§2.11) for the minimal slot-creation slice. See §3.9 for why professional-listing/slots-for-booking live under `/api/bookings/*` rather than `/api/professionals/*` — `/api/availability/*` is a different case (the professional managing their *own* slots, not a customer-facing listing), which is why it stays under `availability`'s own base path instead. |
| Request/response bodies | JSON, `camelCase`. |
| Auth header | `Authorization: Bearer <jwt>` — every endpoint in this doc requires auth. |
| Timestamps in JSON | ISO-8601 / RFC 3339 with offset. |
| Money fields | JSON number, ≤2 decimal places, maps to `NUMERIC(10,2)`. |
| Language of error messages | English; Hebrew presentation is the frontend's job. |
| **New convention this milestone**: path-referenced vs. body-referenced entity ids | An id that names *the resource the URL is about* (`GET /api/issues/{id}`, `GET /api/bookings/orders/{orderId}`, `GET /api/bookings/professionals/{professionalId}/slots`) → **`404 NOT_FOUND`** if it doesn't resolve. An id that's a *field inside a request body* referencing some other entity (`categoryId` in M1/M2, `professionalId`/`slotId` in `POST /api/bookings/orders` below) → **`400 VALIDATION_ERROR`**, consistent with M1/M2's `categoryId` precedent. Stated explicitly here because this milestone is the first to mix both patterns in one doc. |

### 0.1 Role gating pattern (reused, with the M2 fix in mind — corrected against the real
M2 implementation during this finalization pass)

Every endpoint below states its required role(s) explicitly. Implement role checks via the
`common.security.RoleRequiredInterceptor` (`HandlerInterceptor`, runs in `preHandle`)
pattern introduced during Milestone 2 QA — **not** an in-controller-method `RoleGuard`
call — registered per-package via a `bookings.config.BookingsWebConfig`
(`WebMvcConfigurer`), mirroring `issues.config.IssuesWebConfig`/
`storage.config.StorageWebConfig` in *mechanism*. This is a correctness requirement, not a
style preference: calling the role check from inside a controller method body runs *after*
Spring resolves `@Valid`/`@RequestParam` binding, which is exactly the bug M2 QA found and
fixed (`implementation-plan.md` Milestone 2 QA summary).

**Correction found during this finalization pass, checked against the real
`backend/src/main/java/com/pronto/issues/config/IssuesWebConfig.java` and
`.../storage/config/StorageWebConfig.java`/`common/security/RoleRequiredInterceptor.java`
implementations** (the original draft described this loosely enough to be misleading —
fixed here, no behavior change to any endpoint's actual access rule):

- `RoleRequiredInterceptor`'s real constructor takes exactly **one** `requiredRole` string
  and `RoleGuard.requireRole` checks the caller's role against that single value — there is
  **no** "assert role is one of {CUSTOMER, PROFESSIONAL}" mode. The original draft's "the
  interceptor only needs to assert role is CUSTOMER or PROFESSIONAL" description doesn't
  match how the class actually works.
- It doesn't need to. `auth.config.SecurityConfig` already applies
  `.requestMatchers("/actuator/health", "/api/auth/**").permitAll()` /
  `.anyRequest().authenticated()` to the whole app — so *any* request reaching a
  `/api/bookings/**` route past Spring Security has already been proven to carry a valid
  JWT for **some** authenticated user, and v1.0 has exactly two roles. "Reachable by either
  role" is therefore already true for free, with **no `RoleRequiredInterceptor`
  registration at all** — for `cancel` (§2.7), `GET .../orders/{orderId}` (§2.8), and
  `GET .../orders/me` (§2.9), `BookingsWebConfig` registers nothing, and the *real*
  authorization (is this caller a party to this specific order?) happens entirely in the
  service layer once the order is loaded, exactly as the draft already said — only the
  "how the route-level gate abstains" mechanism needed correcting, not that conclusion.
- `IssuesWebConfig`/`StorageWebConfig` could get away with **one** interceptor registered
  on a single blanket pattern (`/api/issues/**` / `/api/storage/**`) because *every*
  Milestone 2 endpoint in each package required the same single role (`CUSTOMER`).
  `bookings` doesn't have that luxury — its endpoints mix `CUSTOMER`-only,
  `PROFESSIONAL`-only, and either-role routes in the same package. `BookingsWebConfig`
  therefore needs **two** separate interceptor registrations, each scoped to precise path
  patterns (not one catch-all `/api/bookings/**`):
  ```java
  registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name()))
          .addPathPatterns("/api/bookings/professionals", "/api/bookings/professionals/*/slots",
                            "/api/bookings/orders");
  registry.addInterceptor(new RoleRequiredInterceptor(UserRole.PROFESSIONAL.name()))
          .addPathPatterns("/api/bookings/orders/*/accept", "/api/bookings/orders/*/reject");
  ```
  (`/api/bookings/orders` as an exact literal pattern matches only `POST .../orders`
  itself — §2.4's create-order call — not `/api/bookings/orders/{id}` or
  `/api/bookings/orders/me`, which are longer paths and don't match a pattern with no
  trailing segment. `addPathPatterns` matches on path only, not HTTP method, which is why
  the create-order pattern must be the bare literal path rather than a `/**` wildcard —
  a wildcard would also swallow the either-role sub-paths it must not gate.)
- **A second, more consequential correction found by this same sanity check**: §2.1 below
  adds `GET /api/issues/{id}` to the **existing** `issues` package, and states its role as
  **either `CUSTOMER` or `PROFESSIONAL`**. But `IssuesWebConfig` (as it exists today, from
  Milestone 2) registers `RoleRequiredInterceptor(CUSTOMER)` on the blanket pattern
  `/api/issues/**` — which would incorrectly `403` a professional calling the new
  `GET /api/issues/{id}`, contradicting §2.1's own access rule. **`pronto-coding` must
  narrow `IssuesWebConfig`'s existing registration** from `/api/issues/**` to the two
  literal Milestone 2 paths it actually needs to restrict to `CUSTOMER`
  (`/api/issues/classify`, `/api/issues` exact) when adding the new endpoint, and register
  no interceptor for `GET /api/issues/{id}` (same "either role, real check is in the
  service layer" reasoning as above — `SecurityConfig`'s blanket `authenticated()` already
  covers it). This is a real, previously-unflagged conflict between this doc's §2.1 and the
  as-built Milestone 2 code, not a hypothetical — flagging explicitly rather than letting
  `pronto-coding` discover it by a failing test.
- §2.10/§2.11 (`POST /api/availability/slots`, `GET /api/availability/slots/me`) live in
  the **`availability` package**, not `bookings` — they need their own
  `availability.config.AvailabilityWebConfig` (`WebMvcConfigurer`), registering a single
  `RoleRequiredInterceptor(PROFESSIONAL)` on `/api/availability/**` (both endpoints share
  the same single role, so — unlike `bookings` — one blanket-pattern registration is
  correct here, the same simple case `IssuesWebConfig`/`StorageWebConfig` were in for
  Milestone 2).

---

## 1. Prerequisite migrations — `V11` and `V12` (both decided, apply in this order)

Two migrations, both required before `pronto-coding` builds anything in this doc. **Apply
`V11` first, then `V12`** — see the ordering note at the end of this section for why, and
confirmation that there is no *functional* dependency between them (the order is a
convention, not a requirement the SQL itself enforces).

### 1.1 `V11__alter_orders_status_add_rejected.sql` — pre-existing gap fix, decided
independently of this doc

**Confirmed requirement, not a recommendation.** `V8__create_orders.sql` (already applied)
only allows the superseded 6-value `order_status` list — missing `REJECTED`, contradicting
`data-model.md` §2.9/§3 item 10. `V8` must **not** be edited in place. New migration,
next free slot (`V1`–`V10` already exist):

```sql
-- V11__alter_orders_status_add_rejected.sql
--
-- Adds 'REJECTED' as a genuine 7th orders.order_status value, per
-- docs/architecture/data-model.md §2.9 / §3 item 10 (user override, 2026-08-12).
-- V8__create_orders.sql (already applied against existing databases) only allowed the
-- superseded 6-value list and must not be edited in place.

ALTER TABLE orders DROP CONSTRAINT ck_orders_status;

ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (order_status IN
    ('PENDING', 'CONFIRMED', 'ON_THE_WAY', 'COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED'));
```

Note: `ck_orders_cancelled_by` (also in `V8`) **already** allows `('CUSTOMER',
'PROFESSIONAL', 'SYSTEM')` — no change needed there; only `ck_orders_status` was wrong.
`V11` touches only this one constraint, not the `slot_id` column added by `V12` below — kept
as two separate migrations so each has one clear purpose.

### 1.2 `V12__add_slot_id_to_orders.sql` — new for this milestone, **APPROVED**
(`pronto-lead`, 2026-08-13)

Gives `orders` an unambiguous reference to the `availability_slots` row it consumed, so
`accept`/`reject`/`cancel` can release it precisely rather than matching on copied
timestamps (§5.4 below implements this as the sole, decided release mechanism — the
timestamp-heuristic fallback described in earlier drafts no longer applies). Nullable
because SOS orders (Milestone 4) never consume an `availability_slots` row at all
(`data-model.md` §2.6/§3 item 5).

```sql
-- V12__add_slot_id_to_orders.sql
--
-- Adds orders.slot_id, an FK to the availability_slots row a Standard order consumed, so
-- accept/reject/cancel can release the correct slot precisely rather than matching on
-- copied (professional_id, booked_start, booked_end) timestamps. Nullable because SOS
-- orders (Milestone 4) never consume an availability_slots row -- data-model.md
-- §2.6/§3 item 5. Approved by pronto-lead, 2026-08-13 -- see
-- docs/architecture/api-contract-bookings.md §6 item 1.

ALTER TABLE orders ADD COLUMN slot_id BIGINT NULL;
ALTER TABLE orders ADD CONSTRAINT fk_orders_slot FOREIGN KEY (slot_id)
    REFERENCES availability_slots (id) ON DELETE SET NULL;
CREATE INDEX idx_orders_slot ON orders (slot_id);
```

### 1.3 Ordering note

`V11` must be numbered/applied before `V12` — this is Flyway's normal sequential
convention (next free version numbers, applied in order), not a functional requirement of
the SQL itself: `V11` only touches `ck_orders_status`, `V12` only adds a new nullable
`slot_id` column + FK + index, and neither migration's `ALTER TABLE` statement references
anything the other one creates or drops. **Verified: no genuine ordering dependency exists
between them** — they could technically apply in either order or even be combined into one
migration without a functional difference. They're kept as two separate, sequentially
numbered files (`V11` before `V12`) purely because `V11` closes a pre-existing gap from
Milestone 1/2 (conceptually "owed" already, independent of this milestone) while `V12` is
net-new scope introduced by this doc — keeping that provenance distinction visible in the
migration history is the only reason for the ordering, not a correctness one.

---

## 2. Error response envelope (reused verbatim from `api-contract.md` §1 / `api-contract-issues.md` §1)

```json
{
  "timestamp": "2026-08-13T12:34:56Z",
  "path": "/api/bookings/orders/900/reject",
  "error": {
    "code": "ORDER_NOT_PENDING",
    "message": "Order 900 is not in PENDING status and cannot be accepted or rejected.",
    "details": null
  }
}
```

### Error code taxonomy — Milestone 3 additions

Codes already defined in `api-contract.md` §1 / `api-contract-issues.md` §1 are **reused
as-is** (`VALIDATION_ERROR`, `FORBIDDEN`, `UNAUTHORIZED`, `NOT_FOUND`, `INTERNAL_ERROR`).
New codes:

| `error.code` | HTTP status | Meaning |
|---|---|---|
| `ISSUE_NOT_BOOKABLE` | 409 | `POST /api/bookings/orders` (or the professional-listing/slot endpoints) called against an issue whose `status != 'OPEN'` — either it already has an active order (`BOOKED`), or it's terminal (`COMPLETED`/`CANCELLED`/`EXPIRED`). Also returned if a concurrent request wins the race to book the issue first (§3.2). |
| `CATEGORY_MISMATCH` | 400 | The referenced `professionalId`'s `category_id` doesn't match the issue's `category_id` (defense against URL/body tampering — the listing/slot endpoints only ever *offer* correctly-matched professionals). |
| `SLOT_UNAVAILABLE` | 409 | The referenced `slotId` doesn't exist for that professional, isn't currently `is_available = true`, has `start_time <= now()`, or lost a concurrency race to another request claiming it first (§3.2). |
| `ORDER_NOT_PENDING` | 409 | `accept`/`reject` called on an order whose `order_status != 'PENDING'` (already decided, or lost a race to a concurrent accept/reject). |
| `ORDER_NOT_CANCELLABLE` | 409 | `cancel` called on an order in a terminal state (`COMPLETED`/`CANCELLED`/`REJECTED`/`EXPIRED`), or by an actor/state combination that isn't permitted (a professional calling `cancel` on a still-`PENDING` order — they must use `reject` instead, §2.7). |

---

## 3. Endpoints

### 2.1 `GET /api/issues/{id}` — new, `issues` package

**Why this belongs here, not silently assumed to exist.** `api-contract-issues.md` §4 and
`overview.md` §6 both flagged this exact gap: M3's booking flow needs to resolve an issue
by id (show its category/description on the professional-listing screen; validate it
belongs to the calling customer and is still bookable). **Decided: add it**, rather than
have `GET /api/bookings/professionals` take a bare `categoryId` — a `categoryId`-only
design would let a customer browse professionals for a category with no issue behind it at
all, breaking the "an order is always created against a persisted, confirmed issue"
invariant (`bookings/README.md`) and losing the ownership/bookable-state check the brief
explicitly asks for. Endpoint conceptually belongs to `issues` (mirrors how M2 kept
`/classify` under `/api/issues/*` even though it's implemented via the `ai` package) —
**`pronto-coding` should add the controller method to `issues`**, not `bookings`. Suggested
follow-up (not done by this doc): fold this section into `api-contract-issues.md` proper
during `pronto-documentation`'s next pass, the same way `data-model.md` flagged suggested
`overview.md` edits without making them itself.

**A second reason this endpoint's authorization rule matters**: `api-contract-issues.md`
§2.4's flagged scope note — "Milestone 3/4 (bookings) will need a professional assigned to
an order to view that order's issue... that authorization rule doesn't exist yet" — is
resolved here for the *issue detail* case (a professional can `GET` an issue they have any
order against, to decide accept/reject or review a confirmed job). The *image bytes*
retrieval case (`GET /api/storage/images/{key}`) is **not** resolved by this doc — remains
genuinely open, see §6 item 3 / §7.

Auth required: **yes**. Role: **either** `CUSTOMER` or `PROFESSIONAL`
— ownership resolved in the service layer, not by a route-level role matcher (§0.1).

**Behavior:**
1. Resolve caller from JWT.
2. Load issue by id → `404 NOT_FOUND` if missing.
3. Authorize:
   - `role = CUSTOMER`: allowed iff `issue.customerId == caller.id`, else `403 FORBIDDEN`.
   - `role = PROFESSIONAL`: allowed iff an `orders` row exists with
     `issue_id = :id AND professional_id = <caller's professional id>` (any status, not
     just active — a professional who was rejected, or completed a past job, can still look
     back at the issue), else `403 FORBIDDEN`.
4. Return the issue, its images, and a summary of its most recent order (if any) — see
   below.

**Response `200`:**
```json
{
  "id": 101,
  "customerId": 42,
  "categoryId": 1,
  "categoryCode": "plumbing",
  "description": "יש נזילת מים מתחת לכיור במטבח",
  "urgencyType": "STANDARD",
  "status": "BOOKED",
  "images": [
    { "id": 501, "imageUrl": "https://.../9f1c2e4a....jpg", "uploadedAt": "2026-08-13T12:30:10Z" }
  ],
  "latestOrder": {
    "id": 900,
    "professionalId": 43,
    "professionalName": "דוד כהן",
    "orderStatus": "PENDING",
    "bookedStart": "2026-08-14T09:00:00Z",
    "bookedEnd": "2026-08-14T11:00:00Z",
    "finalPrice": 150.00,
    "createdAt": "2026-08-13T12:40:00Z"
  },
  "createdAt": "2026-08-13T12:34:56Z",
  "updatedAt": "2026-08-13T12:40:00Z"
}
```

`latestOrder` is the most-recently-created `orders` row for this issue, regardless of its
status (`null` if none exists yet) — **not** restricted to "only if still active." This
single field is what lets the frontend implement the reject-return-to-list branch (see
§4) and the tracking screen without a separate list-orders call: after a rejection, the
customer's client re-fetches this endpoint, sees `latestOrder.orderStatus == 'REJECTED'`
and `issue.status == 'OPEN'`, and shows the professional list again.

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND`.

---

### 2.2 `GET /api/bookings/professionals?issueId={id}`

Auth required: **yes**. Role: **CUSTOMER**.

Professional listing for a Standard booking, filtered by the issue's category. Placed
under `/api/bookings/*` rather than `/api/professionals/*` — see §3.9.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != CUSTOMER`.
2. `issueId` query param required, must parse as a positive integer → `400
   VALIDATION_ERROR` otherwise.
3. Load issue → `404 NOT_FOUND` if missing.
4. `issue.customerId != caller.id` → `403 FORBIDDEN`.
5. `issue.status != 'OPEN'` → `409 ISSUE_NOT_BOOKABLE`.
6. Query `professionals` joined to `users` where `category_id = issue.categoryId` and
   `users.deleted_at IS NULL` (**resolves the flagged gap in `api-contract.md` §2.5**: a
   soft-deleted professional's row previously stayed queryable with nothing filtering it
   out of Standard/SOS listings — this join is the fix, landing exactly where that gap
   said it would need to). Ordered by `base_price ASC` (cheapest first — **judgment call**,
   not specified by any source document; trivial to change, e.g. to `reliability_score DESC
   NULLS LAST`, later).
7. Return the list. **Not** filtered by whether the professional currently has any open
   `availability_slots` (see §3.4 for the reasoning) — a professional with zero slots still
   appears; the slot-selection screen (§2.3) shows "no available times" for them.

**Response `200`:**
```json
{
  "issueId": 101,
  "categoryId": 1,
  "professionals": [
    {
      "professionalId": 43,
      "fullName": "דוד כהן",
      "serviceArea": "תל אביב",
      "basePrice": 150.00,
      "reliabilityScore": 4.50
    }
  ]
}
```

`reliabilityScore` may be `null` (no computation mechanism exists yet, per
`data-model.md` §4 — unrelated to this milestone, just carried forward as a known
null-tolerant field).

**Status codes**: `200` success · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` ·
`403 FORBIDDEN` · `404 NOT_FOUND` · `409 ISSUE_NOT_BOOKABLE`.

---

### 2.3 `GET /api/bookings/professionals/{professionalId}/slots?issueId={id}`

Auth required: **yes**. Role: **CUSTOMER**.

A specific professional's open, future `availability_slots`, for the customer to pick one
to book against.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != CUSTOMER`.
2. `issueId` query param required/valid → `400 VALIDATION_ERROR` otherwise.
3. Load issue → `404 NOT_FOUND`; ownership → `403 FORBIDDEN`; bookable
   (`status == 'OPEN'`) → `409 ISSUE_NOT_BOOKABLE`. (Same three checks as §2.2 — kept
   as defense-in-depth even though the frontend would normally only reach this screen
   after §2.2, consistent with this contract family's established paranoid-validation
   style, e.g. M2's re-validation of `imageKeys` in both `/classify` and `POST
   /api/issues`.)
4. Load professional by `{professionalId}` path variable → `404 NOT_FOUND` if it doesn't
   exist at all (path-referenced id, §0's new convention).
5. `professional.categoryId != issue.categoryId` → `400 CATEGORY_MISMATCH`.
6. Query `availability_slots` where `professional_id = :professionalId AND is_available =
   true AND start_time > now()`, ordered by `start_time ASC`.
7. Return the list.

**Response `200`:**
```json
{
  "professionalId": 43,
  "slots": [
    { "slotId": 77, "startTime": "2026-08-14T09:00:00Z", "endTime": "2026-08-14T11:00:00Z" },
    { "slotId": 78, "startTime": "2026-08-15T13:00:00Z", "endTime": "2026-08-15T14:30:00Z" }
  ]
}
```

An empty `slots` array is a valid, expected response (the professional currently has no
open windows) — not an error.

**Status codes**: `200` success · `400 VALIDATION_ERROR` · `400 CATEGORY_MISMATCH` ·
`401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND` · `409 ISSUE_NOT_BOOKABLE`.

---

### 2.4 `POST /api/bookings/orders`

Auth required: **yes**. Role: **CUSTOMER**.

Creates the order — the Standard-booking "pick this professional, at this slot" action.

**Request:**
```json
{ "issueId": 101, "professionalId": 43, "slotId": 77 }
```

**Field validation:**

| Field | Rule |
|---|---|
| `issueId` | required, positive integer. Must resolve to an issue owned by the caller (§ Behavior step 3) — invalid/nonexistent → `404 NOT_FOUND` (path-style semantics apply here too, even though it's a body field, because the *primary* resource this call acts on is the issue being booked; **exception to the general body-field-id-is-VALIDATION_ERROR rule stated in §0**, called out explicitly since it's the one deliberate inconsistency in this doc). |
| `professionalId` | required, positive integer. Must reference an existing `professionals` row whose `users.deleted_at IS NULL` → `400 VALIDATION_ERROR` otherwise (ordinary body-reference convention, matches M1/M2's `categoryId`). |
| `slotId` | required, positive integer. Existence/availability checked atomically at claim time (§3.2) → `409 SLOT_UNAVAILABLE`, not `400`, since "exists but is no longer available" is a state conflict, not a malformed request. |

**Behavior** (evaluated in this order):
1. Resolve caller; `403 FORBIDDEN` if `role != CUSTOMER`.
2. Validate field presence/shape → `400 VALIDATION_ERROR`.
3. Load issue by `issueId` → `404 NOT_FOUND` if missing.
4. `issue.customerId != caller.id` → `403 FORBIDDEN`.
5. `issue.status != 'OPEN'` → `409 ISSUE_NOT_BOOKABLE`.
6. Load professional by `professionalId` (with `users.deleted_at IS NULL`) → `400
   VALIDATION_ERROR` if missing/deleted.
7. `professional.categoryId != issue.categoryId` → `400 CATEGORY_MISMATCH`.
8. **Atomically claim the slot** (single transaction from here through step 10):
   `UPDATE availability_slots SET is_available = false, updated_at = now() WHERE id =
   :slotId AND professional_id = :professionalId AND is_available = true AND start_time >
   now()`. If the affected-row count is `0` → `409 SLOT_UNAVAILABLE`, roll back, return
   immediately (covers: slot doesn't exist, belongs to a different professional, already
   claimed, already in the past, or lost a concurrency race to a simultaneous request —
   §3.2).
9. **Atomically transition the issue**: `UPDATE issues SET status = 'BOOKED', updated_at =
   now() WHERE id = :issueId AND status = 'OPEN'`. If affected rows `= 0` → the issue was
   booked by a concurrent request between step 5 and here → roll back the whole
   transaction (including the slot claim from step 8) → `409 ISSUE_NOT_BOOKABLE`.
10. Insert the `orders` row: `issue_id`, `customer_id = caller.id`, `professional_id`,
    `booked_start = slot.startTime`, `booked_end = slot.endTime`, `order_status =
    'PENDING'`, `cancelled_by = NULL`, `final_price = professional.basePrice`
    (initialized from the professional's standing price offer, per `data-model.md` §2.9 —
    not editable by any endpoint in this milestone), `slot_id = :slotId` (per `V12`, §1.2 —
    always set for a Standard order).
11. Commit. Return `201`.

**Response `201`:**
```json
{
  "id": 900,
  "issueId": 101,
  "customerId": 42,
  "professionalId": 43,
  "orderStatus": "PENDING",
  "bookedStart": "2026-08-14T09:00:00Z",
  "bookedEnd": "2026-08-14T11:00:00Z",
  "finalPrice": 150.00,
  "cancelledBy": null,
  "createdAt": "2026-08-13T12:40:00Z",
  "updatedAt": "2026-08-13T12:40:00Z"
}
```

**Status codes**: `201` success · `400 VALIDATION_ERROR` · `400 CATEGORY_MISMATCH` ·
`401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND` · `409 ISSUE_NOT_BOOKABLE` ·
`409 SLOT_UNAVAILABLE`.

**A customer may re-pick the same professional after a rejection.** Nothing in this
endpoint (or anywhere else) prevents creating a second `orders` row against the same issue
naming the same `professionalId` that previously rejected it — `data-model.md` §2.9
explicitly allows an issue to "accumulate more than one `orders` row over time," and no
source document says a professional can't be asked again. Not blocked; also not
specially encouraged (no dedup/"already declined you" annotation on the listing, §2.2) —
plain, unfiltered re-listing.

---

### 2.5 `POST /api/bookings/orders/{orderId}/accept`

Auth required: **yes**. Role: **PROFESSIONAL**.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Load order by `orderId` → `404 NOT_FOUND` if missing.
3. Resolve caller's `professionals.id` via `ProfessionalRepository.findByUserId(caller.id)`
   (already exists — no new lookup mechanism needed). `order.professionalId !=` that id →
   `403 FORBIDDEN`.
4. Atomically transition: `UPDATE orders SET order_status = 'CONFIRMED', updated_at =
   now() WHERE id = :orderId AND order_status = 'PENDING'`. Affected rows `0` → `409
   ORDER_NOT_PENDING` (already decided, or lost a race to a concurrent accept/reject on the
   same order — extremely unlikely in practice for a single professional's own action, but
   the same atomic-guard mechanism as §2.4 is used uniformly, not conditionally).
5. `issues.status` is **not** touched — it's already `'BOOKED'` (set at order creation,
   §2.4 step 9) and stays `'BOOKED'` through `CONFIRMED`/`ON_THE_WAY`, per `data-model.md`
   §3 item 8's lifecycle table.
6. Return `200` with the updated order (same shape as §2.4's response, `orderStatus:
   "CONFIRMED"`).

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND` · `409 ORDER_NOT_PENDING`.

---

### 2.6 `POST /api/bookings/orders/{orderId}/reject`

Auth required: **yes**. Role: **PROFESSIONAL**.

Implements the **exact** semantics from `data-model.md` §3 item 10, first bullet — binding,
not deviated from: a professional declining a still-`PENDING` request →
`PENDING → REJECTED`, `cancelled_by` stays `NULL`.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Load order → `404 NOT_FOUND` if missing.
3. Ownership check, same as §2.5 step 3 → `403 FORBIDDEN`.
4. Atomically transition: `UPDATE orders SET order_status = 'REJECTED', updated_at =
   now() WHERE id = :orderId AND order_status = 'PENDING'`. Affected rows `0` → `409
   ORDER_NOT_PENDING` (e.g. the professional already accepted it, or the order already
   expired via a future M5 sweep — `reject` is only ever valid from `PENDING`).
5. **Release the slot**: see §3.4 for the exact mechanism (via `order.slotId`, `V12`, §1.2).
6. **Revert the issue to `OPEN`**: `UPDATE issues SET status = 'OPEN', updated_at = now()
   WHERE id = :issueId`. Unconditional (no `WHERE status = 'BOOKED'` guard needed) — see
   §3.3 for why the single-active-order-per-issue invariant makes this safe.
7. Return `200` with the updated order (`orderStatus: "REJECTED"`, `cancelledBy: null`).

**This is the reject → return-to-list branch's server-side half** — see §4 for the full
client-observable flow.

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND` · `409 ORDER_NOT_PENDING`.

---

### 2.7 `POST /api/bookings/orders/{orderId}/cancel`

Auth required: **yes**. Role: **CUSTOMER or PROFESSIONAL** (actor determined by which party
the caller is, not by a route-level role matcher — §0.1).

Implements `data-model.md` §3 item 10's second and third bullets: a professional backing
out of an already-`CONFIRMED` (or later) order, or a customer backing out at any stage,
both go through `CANCELLED` (never `REJECTED` — that status is reserved for the
PENDING-decline case only, handled by §2.6).

**Behavior:**
1. Resolve caller (no role gate at the route level — either role may call this).
2. Load order → `404 NOT_FOUND` if missing.
3. Determine actor:
   - `caller.role == CUSTOMER` and `order.customerId == caller.id` → actor `CUSTOMER`.
   - `caller.role == PROFESSIONAL` and `order.professionalId == <caller's professional
     id>` → actor `PROFESSIONAL`.
   - Neither → `403 FORBIDDEN`.
4. Check the actor/state combination is permitted:
   - `CUSTOMER` may cancel from `PENDING`, `CONFIRMED`, or `ON_THE_WAY`.
   - `PROFESSIONAL` may cancel **only** from `CONFIRMED` or `ON_THE_WAY` — a professional
     backing out of a still-`PENDING` order must use `reject` (§2.6) instead, so the two
     endpoints stay unambiguous about which one produces `REJECTED` vs. `CANCELLED`.
   - Any other combination (e.g. professional attempting `cancel` on a `PENDING` order, or
     either actor attempting it on a terminal-state order) → `409
     ORDER_NOT_CANCELLABLE`.
5. Atomically transition: `UPDATE orders SET order_status = 'CANCELLED', cancelled_by =
   :actor, updated_at = now() WHERE id = :orderId AND order_status = :expectedCurrentStatus`
   (the status read in step 4, re-checked here as the concurrency guard). Affected rows `0`
   → `409 ORDER_NOT_CANCELLABLE` (lost a race).
6. Release the slot — same mechanism as §2.6 step 5.
7. Revert the issue to `OPEN` — same as §2.6 step 6, unconditional.
8. Return `200` (`orderStatus: "CANCELLED"`, `cancelledBy: "CUSTOMER" | "PROFESSIONAL"`).

**Not built this milestone**: any endpoint or trigger that produces `cancelled_by =
'SYSTEM'`. The value remains valid in the `ck_orders_cancelled_by` constraint (already true
in the applied `V8`, unaffected by `V11`) for a future automated process — distinct from
the `EXPIRED` sweep (`data-model.md` §3 item 8), which uses its own status and doesn't go
through `CANCELLED` at all.

**Also not built this milestone**: an "abandon this issue entirely" action distinct from
cancelling one order. `data-model.md` §3 item 8 defines issue-level `CANCELLED` as "the
customer gave up on the issue entirely," a different, still-open concept from an
individual order being cancelled (which reverts the issue to `OPEN`, not to
issue-level `CANCELLED`) — no endpoint here produces issue-level `CANCELLED`, consistent
with that item still being an open gap-fill, not resolved by this doc either.

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND` · `409 ORDER_NOT_CANCELLABLE`.

---

### 2.8 `GET /api/bookings/orders/{orderId}`

Auth required: **yes**. Role: **CUSTOMER or PROFESSIONAL** (party-to-the-order check, same
pattern as §2.7 step 3, read-only). This is the **tracking/status endpoint** — status only,
no GPS/map, per the hard exclusion.

**Behavior:**
1. Resolve caller.
2. Load order → `404 NOT_FOUND` if missing.
3. Caller must be the order's customer or its professional → else `403 FORBIDDEN`.
4. Return the order, enriched with display-friendly names (avoids the client needing
   follow-up calls just to show "David Cohen" instead of `professionalId: 43`).

**Response `200`:**
```json
{
  "id": 900,
  "issueId": 101,
  "customerId": 42,
  "customerName": "ישראל ישראלי",
  "professionalId": 43,
  "professionalName": "דוד כהן",
  "orderStatus": "CONFIRMED",
  "bookedStart": "2026-08-14T09:00:00Z",
  "bookedEnd": "2026-08-14T11:00:00Z",
  "finalPrice": 150.00,
  "cancelledBy": null,
  "createdAt": "2026-08-13T12:40:00Z",
  "updatedAt": "2026-08-13T12:41:30Z"
}
```

This is the endpoint a short-polling client (per `overview.md` §3.3) hits every 3–5s once
an order exists — **the actual polling loop/scheduler is Milestone 5's job**
(`notifications` package), this endpoint just needs to exist and be cheap (single indexed
PK lookup) for that milestone to build on. `updated_at` is what changes on every status
transition, exactly as `data-model.md` §2.9 designed it to.

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND`.

---

### 2.9 `GET /api/bookings/orders/me`

Auth required: **yes**. Role: **CUSTOMER or PROFESSIONAL** (self-scoped by role).

**Scope note, stated explicitly since it goes slightly beyond the literal M3 bullet list in
`implementation-plan.md`** (which lists "incoming-requests view" under **Milestone 6**):
this is a thin, generic **read API** — `GET /api/bookings/orders/me`, no dashboard UI, no
`features/dashboard` frontend work (out of scope, backend-only milestone anyway). It's
included here because it's a **functional prerequisite**, not a nice-to-have: without some
way for a professional to discover which orders are `PENDING` against them, `accept`/
`reject` (§2.5/§2.6) is only exercisable by a caller who already knows an `orderId` out of
band (e.g. a QA script that captured it from the `POST /api/bookings/orders` response) —
not a real, discoverable flow for an actual professional. One endpoint, both roles: the
**customer's** "my active orders" need and the **professional's** "incoming requests" need
are the same underlying query shape (orders where I'm a party, optionally filtered by
status), so Milestone 6 is expected to build its dashboard UI **on top of this same
endpoint**, not need a new one — this isn't stepping on M6's scope, just building the read
API home it will consume.

**Query params**: `status` (optional, one of the 7 `order_status` values — if provided,
filters to that status only; if omitted, returns all of the caller's orders).

**Behavior:**
1. Resolve caller.
2. `role = CUSTOMER`: query `orders WHERE customer_id = caller.id [AND order_status =
   :status]`.
   `role = PROFESSIONAL`: resolve professional id, query `orders WHERE professional_id =
   :professionalId [AND order_status = :status]`.
3. Order by `created_at DESC` (**judgment call**, easy to change — e.g. to `updated_at
   DESC` if "most recently changed" proves more useful for a professional's incoming-work
   view once M6 builds on it).
4. Return the list. No pagination this milestone (fine at MVP scale — flag for Milestone 7
   hardening if it ever isn't).

**Response `200`:**
```json
{
  "orders": [
    {
      "id": 900,
      "issueId": 101,
      "orderStatus": "PENDING",
      "bookedStart": "2026-08-14T09:00:00Z",
      "bookedEnd": "2026-08-14T11:00:00Z",
      "finalPrice": 150.00,
      "createdAt": "2026-08-13T12:40:00Z"
    }
  ]
}
```

(Field set intentionally lean — this is a list/summary endpoint; use §2.8 for full detail
on one order, matching the same "list is lean, detail is rich" pattern M2 didn't need but
M1 implicitly used for `/api/users/me` vs. nothing-else-existing.)

**Status codes**: `200` success · `400 VALIDATION_ERROR` (invalid `status` value) ·
`401 UNAUTHORIZED`.

---

### 2.10 `POST /api/availability/slots` — new, `availability` package

**Approved, `pronto-lead`, 2026-08-13 (§6 item 2, option (a)).** A professional creates one
bookable Standard advance-booking window. This is the **entire** create-side scope of the
Milestone-3 `availability` slice — no edit, no delete, no toggle-availability action, no
listing-other-professionals'-slots capability. It exists solely so §2.3/§2.4 above have
real `availability_slots` rows to book against without a raw-SQL QA workaround. Full
CRUD, richer calendar semantics, and any dashboard UI remain **Milestone 6**'s job — see
`availability/README.md`'s updated status line.

Auth required: **yes**. Role: **PROFESSIONAL**.

**Request:**
```json
{ "startTime": "2026-08-14T09:00:00Z", "endTime": "2026-08-14T11:00:00Z" }
```

**Field validation:**

| Field | Rule |
|---|---|
| `startTime` | required, ISO-8601/RFC 3339 timestamp with offset, parseable → `400 VALIDATION_ERROR` otherwise. Must be strictly in the future (`startTime > now()` at request time) → `400 VALIDATION_ERROR` if not (mirrors the `start_time > now()` condition §2.3 already filters listings by — a slot created in the past could never be listed or booked, so rejecting it at creation time is more useful than silently persisting a dead row). |
| `endTime` | required, same timestamp format → `400 VALIDATION_ERROR` otherwise. Must satisfy `endTime > startTime` → `400 VALIDATION_ERROR` otherwise (matches the DB-level `CHECK (end_time > start_time)` on `availability_slots`, `data-model.md` §2.5 — validated at the API layer too so the error is a clean `400` rather than a DB constraint violation surfacing as a `500`). |

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Validate field presence/shape/ordering per the table above → `400 VALIDATION_ERROR`.
3. Resolve caller's `professionals.id` via `ProfessionalRepository.findByUserId(caller.id)`
   (same mechanism as §3.5 — no new lookup needed).
4. Insert a new `availability_slots` row: `professional_id = <resolved id>`, `start_time`,
   `end_time`, `is_available = true` (default, per `data-model.md` §2.5 — every newly
   created slot starts out bookable).
5. Return `201`.

**No overlap/double-booking validation against the professional's own existing slots** —
not requested by any source document, and out of scope for this narrow Milestone-3 slice;
a professional can create two overlapping slots today. Flagged as a candidate for
Milestone 6's richer calendar semantics, not built here.

**Response `201`:**
```json
{
  "id": 77,
  "professionalId": 43,
  "startTime": "2026-08-14T09:00:00Z",
  "endTime": "2026-08-14T11:00:00Z",
  "isAvailable": true,
  "createdAt": "2026-08-13T12:00:00Z"
}
```

**Status codes**: `201` success · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` ·
`403 FORBIDDEN`.

---

### 2.11 `GET /api/availability/slots/me` — new, `availability` package

**Approved, `pronto-lead`, 2026-08-13 (§6 item 2, option (a)).** Lets the professional who
just created slots via §2.10 verify what they created — the read-side counterpart, and
(together with §2.10) the complete Milestone-3 `availability` scope. **Not** the
professional-facing "manage my calendar" dashboard view (that's Milestone 6) — this is a
bare, unfiltered self-listing.

Auth required: **yes**. Role: **PROFESSIONAL**.

**Query params**: none this milestone. Returns **all** of the caller's slots (past,
future, available, and already-claimed/unavailable) — no `status`/date-range filter, no
pagination, consistent with this doc's "no pagination this milestone" convention elsewhere
(§2.9, §7). A Milestone 6 dashboard view is expected to add filtering/pagination on top of
this same underlying query shape, not replace it — same "read API home, richer UI consumes
it later" relationship as §2.9's note about the future professional dashboard.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Resolve caller's `professionals.id` (same mechanism as §2.10 step 3).
3. Query `availability_slots WHERE professional_id = :professionalId`, ordered by
   `start_time ASC`.
4. Return the list.

**Response `200`:**
```json
{
  "slots": [
    {
      "id": 77,
      "startTime": "2026-08-14T09:00:00Z",
      "endTime": "2026-08-14T11:00:00Z",
      "isAvailable": true,
      "createdAt": "2026-08-13T12:00:00Z"
    },
    {
      "id": 76,
      "startTime": "2026-08-13T09:00:00Z",
      "endTime": "2026-08-13T10:00:00Z",
      "isAvailable": false,
      "createdAt": "2026-08-12T08:00:00Z"
    }
  ]
}
```

An empty `slots` array (a professional who hasn't created any yet) is a valid, expected
response — not an error.

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN`.

---

## 4. End-to-end flow summary (PRD §3.4, including the reject → return-to-list branch)

For clarity, the full Standard-path sequence this contract implements:

1. Customer has a confirmed `issues` row (Milestone 2, `status = 'OPEN'`).
2. `GET /api/bookings/professionals?issueId=` (§2.2) → professional cards with price
   offers.
3. Customer picks one → `GET /api/bookings/professionals/{id}/slots?issueId=` (§2.3) →
   available time windows.
4. Customer picks a slot → `POST /api/bookings/orders` (§2.4) → `orders` row created
   (`PENDING`), slot claimed, `issues.status → 'BOOKED'`.
5. Professional sees it via `GET /api/bookings/orders/me?status=PENDING` (§2.9) →
   `accept` (§2.5) or `reject` (§2.6).
   - **Accept**: `order_status → 'CONFIRMED'`. Customer's polling `GET
     /api/bookings/orders/{orderId}` (§2.8) observes the change — this is the
     confirmation flow.
   - **Reject**: `order_status → 'REJECTED'`, slot released, `issues.status → 'OPEN'`.
     Customer's client (polling either §2.8 on the known `orderId`, or re-`GET
     /api/issues/{id}` per §2.1's `latestOrder`/`status` fields) observes
     `issue.status == 'OPEN'` again and returns to step 2 — **the customer may browse the
     (now again-`OPEN`) issue's professional list and pick a different professional, or the
     same one again**, creating a second `orders` row against the same issue (§2.4's
     closing note).
6. Once `CONFIRMED`, either party may still `cancel` (§2.7) → `CANCELLED`, slot released,
   `issues.status → 'OPEN'` (same return-to-list branch as a rejection, different terminal
   status).
7. Progression from `CONFIRMED` onward to `ON_THE_WAY`/`COMPLETED` is **not** built by any
   endpoint in this doc — **confirmed** (`pronto-lead`, 2026-08-13, §6 item 4) as
   Milestone 6's scope, not Milestone 3's; see §6 item 4 for the full reasoning.

---

## 5. Cross-cutting mechanisms

### 3.1 Role gating — see §0.1 above (not repeated).

### 3.2 Atomic state transitions — one consistent mechanism, not ad hoc per endpoint

Every state change in this doc (slot claim, issue `OPEN → BOOKED`, every `orders.order_status`
transition) uses the same pattern: a single `UPDATE ... WHERE <current-state-guard>`
statement, checking the affected-row count, inside one `@Transactional` service method.
This is Postgres's plain read-committed-safe "conditional update" idiom — no explicit
`SELECT ... FOR UPDATE` locking is needed, and no optimistic-locking `@Version` column is
introduced (would be a schema change; the `WHERE`-clause guard achieves the same
correctness without one). Applied uniformly so `pronto-coding` doesn't have to invent this
per-endpoint or get it subtly wrong on one of the seven (§2.4/§2.5/§2.6/§2.7's four
transition points, each internally two conditional updates in the create-order case).

### 3.3 Single-active-order-per-issue invariant

`POST /api/bookings/orders` (§2.4 step 5/9) only ever succeeds against an issue whose
`status == 'OPEN'`, and `issues.status` only ever becomes `'BOOKED'` as part of that same
transaction. Consequently: **at most one order in `PENDING`/`CONFIRMED`/`ON_THE_WAY` can
exist for a given issue at any time.** This is what makes the unconditional `issues.status
→ 'OPEN'` in `reject`/`cancel` (§2.6/§2.7, no "are there other active orders?" check) safe —
there structurally cannot be another one. If a future milestone ever relaxes this
invariant (e.g. "request multiple professionals for the same issue at once" — not
requested by any source document, not designed here), that revert-to-`OPEN` logic would
need to change too; flagging the dependency so it isn't silently broken later.

### 3.4 Slot lifecycle: claim on create, release on reject/cancel, kept on accept/complete

- **Claimed** (`is_available: true → false`) at order-creation time (§2.4 step 8), **not**
  deferred until the professional accepts. Reasoning: two different customers must not be
  able to both hold a `PENDING` request against the same slot simultaneously — claiming
  early (at request time, not acceptance time) is what a normal "book a specific time
  window" UX implies, and matches how the slot stops appearing in §2.3's listing for anyone
  else immediately after this customer picks it.
- **Released** (`is_available: false → true`) on `reject` (§2.6) and `cancel` (§2.7) —
  the customer's chosen time window becomes bookable by someone else again once this
  attempt didn't pan out.
- **Never released** on `accept`/`COMPLETED` — the professional's calendar window is
  legitimately consumed once the job is confirmed and going ahead.
- **Mechanism for finding *which* slot row to release — decided (`pronto-lead`,
  2026-08-13, §6 item 1): the `orders.slot_id` column (`V12`, §1.2) is the sole release
  mechanism, no fallback branch.** `UPDATE availability_slots SET is_available = true,
  updated_at = now() WHERE id = order.slotId`. For a Standard order, `slot_id` is always
  set at creation time (§2.4 step 10), so this is unconditional for Standard orders; for a
  future SOS order (Milestone 4) `slot_id` is `NULL` and this step is a no-op (§3.7 below).
  The previously-considered `(professional_id, start_time, end_time)` timestamp-heuristic
  fallback is **not built** — it's superseded by this decision, not a remaining option.
  `slot_id` is purely an internal lookup aid, never exposed in any JSON body above.
- **Milestone 5's forward dependency, stated here so it isn't rediscovered**: when the
  `EXPIRED` sweep job (`data-model.md` §3 item 8, owned by Milestone 5) flips a `PENDING`
  order to `EXPIRED`, it must release the slot using this **same** mechanism — ideally by
  calling the same domain-service method `reject`/`cancel` call internally, not
  reimplementing the release logic a third time.

### 3.5 Professional identity resolution

Every professional-facing endpoint (`accept`, `reject`, the `PROFESSIONAL` branch of
`cancel`/`GET .../{orderId}`/`GET .../me`) resolves the caller's `professionals.id` from
the JWT's `sub` (user id) via `professionals.repository.ProfessionalRepository
.findByUserId(callerId)` — already exists (`backend/.../professionals/repository/
ProfessionalRepository.java`), no new lookup mechanism needed. If that lookup returns
empty (a `CUSTOMER`-role token somehow reaching a professional-gated code path) — should
not be reachable given §0.1's role interceptor, but if it is, treat as `403 FORBIDDEN`,
not a `500`.

### 3.6 `issues.status` transition table (this milestone's contribution)

| Trigger | `issues.status` | Endpoint |
|---|---|---|
| Issue confirmed/created (Milestone 2) | `OPEN` | `POST /api/issues` |
| Order created (`PENDING`) | `OPEN → BOOKED` | §2.4 |
| Order accepted (`CONFIRMED`) | stays `BOOKED` | §2.5 |
| Order rejected (`REJECTED`) | `BOOKED → OPEN` | §2.6 |
| Order cancelled (`CANCELLED`) | `BOOKED → OPEN` | §2.7 |
| Order completed (`COMPLETED`) | `BOOKED → COMPLETED` | **not built this milestone** — no endpoint here reaches `orders.order_status = 'COMPLETED'` at all; **confirmed** Milestone 6 scope (§6 item 4). Noted so Milestone 6 knows the mapping is already decided, just not yet wired to an endpoint. |
| Order expires (`EXPIRED`, sweep) | `BOOKED → EXPIRED` (unless customer already rebooked) | **Milestone 5's job**, per `data-model.md` §3 item 8 — restated here only as a forward-reference, not designed in this doc. |

### 3.7 Where this design already generalizes to SOS (Milestone 4) vs. where it doesn't

Stated explicitly per the task brief's "don't paint yourself into a corner for M4"
instruction:

- **Already generalizes, no rework needed**: `accept`/`reject`/`cancel`/`GET
  .../{orderId}`/`GET .../me` (§2.5–§2.9) are all urgency-agnostic — they operate on an
  `orders` row by id/ownership, with no `urgency_type` branching anywhere in their logic.
  Once Milestone 4 creates SOS orders (via its own creation endpoint), these five endpoints
  work on them identically, unchanged.
- **`orders.slot_id` (§1.2, `V12`) is nullable specifically because of SOS.** SOS orders
  will never consume an `availability_slots` row (SOS uses `sos_availability`, a live
  toggle — `data-model.md` §2.6/§3 item 5) — so `slot_id` tolerates `NULL`, which `V12`
  already allows. `reject`/`cancel`'s slot-release step (§3.4) runs
  `UPDATE availability_slots SET ... WHERE id = order.slotId` unconditionally; when
  `order.slotId IS NULL` (a future SOS order), that `WHERE id = NULL` predicate matches zero
  rows by ordinary SQL `NULL`-comparison semantics, so it's already a safe no-op for SOS
  orders with no extra `IS NOT NULL` branch needed in application code — not a bug waiting
  for Milestone 4 to trip over.
- **Not generalized, M4 needs a net-new creation path**: §2.2–§2.4 (professional
  listing, slot listing, order creation) are Standard-specific by design — SOS's
  "professional listing" is filtered by `sos_availability.is_available = true`, not
  `availability_slots`, and has no slot-selection step at all (an SOS order's
  `booked_start` is presumably "now"/request time, `booked_end` stays `NULL` per
  `data-model.md` §2.9). Milestone 4 will add its own `POST /api/bookings/sos-orders` (or
  similar) rather than reusing §2.4 — not designed here, flagged only so it isn't assumed
  to fall out of this doc for free.

### 3.8 What "confirmation flow" means in this contract

`implementation-plan.md`'s M3 bullet list says "confirmation flow" as a distinct item from
accept/reject. This doc doesn't introduce a separate "confirm" endpoint — **"confirmation"
is the customer-observable *effect* of the professional's `accept` (§2.5) landing**,
visible via polling §2.8/§2.1. No additional server-side step exists between accept and the
order being considered "confirmed" (no double-confirmation, no customer-side ack of the
acceptance) — flagged as an interpretation, not something any source document spells out
as a separate step, but a natural reading of `order_status = 'CONFIRMED'` already being the
confirmation.

### 3.9 Why professional-listing/slots live under `/api/bookings/*`, not `/api/professionals/*`

The `professionals` package (per `overview.md` §4) owns "professional profile, service
area, reliability score" — general profile data, not booking-context-specific matching.
§2.2/§2.3's listing is always issue-scoped (filtered by an issue's category, only
meaningful mid-booking-flow) rather than a general "browse all professionals" directory
feature — placing it under `/api/bookings/*` (implemented in the `bookings` package, reading
from `professionals`/`availability_slots` as needed) keeps the booking journey's endpoints
discoverable together, mirroring the M2 precedent of keeping `/classify` under
`/api/issues/*` even though it's backed by the separate `ai` package.

---

## 6. Decisions — resolution record (`pronto-lead`, 2026-08-13)

The original draft raised four items for explicit sign-off before `pronto-coding` could
start. All four have now been resolved. Kept as a numbered record for traceability (why
the doc reads the way it does above), **not** a pending-sign-off list any more — items 1,
2, and 4 are fully closed; item 3 is confirmed to remain a genuinely open question, but one
that's explicitly **out of Milestone 3's scope**, not a blocker for `pronto-coding` to
start building everything else in this doc.

1. **`orders.slot_id` — APPROVED.** Built as `V12__add_slot_id_to_orders.sql`, exactly as
   originally drafted (nullable, FK → `availability_slots(id)` `ON DELETE SET NULL`,
   indexed) — see §1.2 for the final migration text and §5.4 for how `reject`/`cancel` use
   it as the sole, decided slot-release mechanism (the timestamp-heuristic alternative
   described in the original draft is superseded, not built).

2. **Minimal `availability` slice pulled into Milestone 3 — APPROVED, option (a).** A
   minimal `AvailabilitySlot` JPA entity + repository (read-focused, needed by `bookings`
   regardless) plus exactly two endpoints: `POST /api/availability/slots` (a professional
   creates one slot) and `GET /api/availability/slots/me` (list their own slots). The full
   contract-level spec for these two endpoints (role gating, field validation, behavior
   steps, response shape, status codes — matching the rigor of every other endpoint in this
   doc) is now written out in full in §2.10/§2.11 below, resolving the gap the original
   draft flagged (it previously only described this slice at a high level). Explicitly
   **not** the Milestone 6 dashboard — no edit/delete/toggle actions, no UI; full CRUD and
   the dashboard UI remain Milestone 6's job, per `availability/README.md`'s updated status
   line.

3. **Professional viewing issue *images* before deciding accept/reject — STAYS OPEN,
   confirmed out of Milestone 3's scope.** Not resolved by this pass, and deliberately not
   designed or built in Milestone 3 — `pronto-lead`'s explicit instruction. §2.1
   (`GET /api/issues/{id}`) still resolves the *issue detail* authorization gap (a
   professional can see an issue's category/description once they have any order against
   it), but the **image bytes** endpoint (`GET /api/storage/images/{key}`, M2) remains
   hard-restricted to `role = CUSTOMER` — a professional still has no way to view an
   issue's photos at all, in any milestone, as of this doc. Not blocking Milestone 3's core
   acceptance criteria (the PRD's Standard flow doesn't require photo access before
   accept/reject, only description/category, which §2.1 already exposes). **Kept in active
   open-question tracking, not dropped** — see also §7's open-items list, which now
   restates it so it isn't only findable inside this resolution record.

4. **`ON_THE_WAY`/`COMPLETED` progression endpoints deferred to Milestone 6 — CONFIRMED as
   the correct reading.** `pronto-lead` has confirmed this doc's interpretation: Milestone 3
   covers the request/accept/reject/cancel/track surface through `CONFIRMED`, plus the
   terminal `CANCELLED`/`REJECTED` statuses; forward progression of an already-confirmed job
   to `ON_THE_WAY`/`COMPLETED` stays Milestone 6's job, matching
   `implementation-plan.md`'s existing milestone bullet split. No design change to this
   doc was needed — the interpretation was already correct, just unconfirmed until now.

---

## 7. Open items / risks (lower-stakes, don't block `pronto-coding`, but flagging)

- **STILL OPEN, genuinely unresolved (restated from §6 item 3 so it isn't only findable
  there): professional viewing issue images before deciding accept/reject.** No endpoint
  anywhere lets a professional view an issue's photos — `GET /api/storage/images/{key}`
  remains `role = CUSTOMER`-only. Confirmed explicitly out of Milestone 3's scope (not
  designed or built here); not blocking, since the PRD's Standard flow doesn't require
  photo access before accept/reject. Needs a decision (and, per §6 item 3's original
  writeup, a choice of lookup mechanism) before it's built, in a later milestone.
- **Professional-listing ordering (`base_price ASC`, §2.2) and orders-list ordering
  (`created_at DESC`, §2.9) are judgment calls**, not specified by any source document —
  trivial to change later, no migration implied either way.
- **No pagination on any list endpoint** (§2.2, §2.3, §2.9) — acceptable at MVP/QA scale;
  flag as a Milestone 7 hardening candidate if professional/slot/order counts ever grow
  large enough to matter.
- **No dedup/exclusion of a professional who already rejected this issue from the listing**
  (§2.2) — a customer can see and re-request the same professional who just declined them.
  Not requested to be filtered by any source document; judgment call to leave unfiltered.
- **`final_price` is fixed at `professional.basePrice` at order-creation time, with no
  endpoint to adjust it afterward** (e.g. after an on-site inspection) — `data-model.md`
  §2.9 explicitly calls this a possible future workflow ("application logic, not enforced
  here"); not built this milestone, not blocking.
- **`GET /api/bookings/orders/me`'s `status` filter accepts any of the 7 values including
  `EXPIRED`/`COMPLETED`** even though no endpoint in this doc ever produces those — harmless
  (an always-empty filter result until Milestone 5/6 exist), not worth special-casing.
