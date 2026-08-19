# Professional Weekly Availability Calendar — Design

Status: **finalized — all three open questions resolved by explicit user decision
(2026-08-18). Ready for `pronto-coding`; one continuous, ungated milestone sequence
(M1-M6, §10).**
Written by `pronto-planning`. Implements the product spec pasted into this task's brief
(referred to below as "the product spec," section numbers `§N` refer to it unless stated
otherwise). Cross-checked against `docs/architecture/overview.md` (resolved-decisions
table, §2), `docs/architecture/data-model.md` (§2.5/§2.6/§2.9), and
`docs/architecture/api-contract-bookings.md` (§2.10-2.19, §3.2-3.4) as of `V24`/Milestone 8
+ MS9. Does **not** touch `sos_availability`, `reviews`, `favorites`, `notifications`, or
any payment/GPS concept — all confirmed out of scope.

**Update (2026-08-18), by `pronto-planning`.** The original design pass (the rest of this
doc, mostly unchanged) flagged three open questions in §9 — the missing `phone` field
(§9.1), the Standard-order-creation replacement forced by retiring `availability_slots`
(§9.2), and the business-timezone constant (§9.5) — and gated the former "M5" milestone on
§9.2's resolution. **The user has now decided all three explicitly.** This revision:
records each decision in place in §9 (kept at the same subsection numbers so existing
cross-references throughout this doc stay valid), folds the former gated M5 into the main
milestone sequence as M2 (backend) and M6 (frontend) — see §10, now M1-M6, nothing gated —
adds a new `users.phone` column and its privacy rule, finalizes `Asia/Jerusalem` as the
business timezone, and picks a fixed 60-minute default job duration with the rationale
recorded prominently in §9.2.1 (this is a real product decision made without direct
source-document backing — flagged there, not buried, and repeated in this update note for
visibility). No part of this doc is provisional after this revision.

**Read §9 for the full decision record before implementing anything that touches order
creation, availability listing, or the `phone` field.**

---

## 0. TL;DR for `pronto-lead`

- New tables: `professional_working_hours`, `professional_availability_blocks`. New
  Postgres extension: `btree_gist` (for two exclusion constraints — block/block overlap,
  and, separately, order/order overlap).
- `availability_slots` and its four existing endpoints (`POST`/`GET .../me`/`PUT`/`DELETE
  /api/availability/slots*`) are **kept, unmodified, not dropped** — historical/audit data
  and any still-referenced order rows keep working exactly as today. They stop being
  reachable from the professional-facing calendar UI once M4 (§10) lands, and become fully
  vestigial (no code path creates new rows anymore) once M6 (§10) ships — left in place
  regardless, cheap insurance, zero ongoing cost. `sos_availability` is completely
  untouched, per the task brief.
- New consolidated read endpoint, `GET /api/availability/calendar?from=&to=`, is the single
  new "big" professional-facing endpoint — justified in §4 against §24's "don't add
  unnecessarily" instruction.
- Double-booking protection (§8 of the product spec) is enforced by a Postgres **exclusion
  constraint** directly on `orders`, not application logic alone — see §6. This constraint
  is now the **sole authoritative protection** for Standard order creation (§9.2.2), not
  merely defense-in-depth.
- **Standard-booking order creation is reworked (§9.2, DECIDED — the design's own Option 2
  is built)**: `POST /api/bookings/orders` accepts a direct `bookedStart`; `bookedEnd` is
  derived server-side from a **fixed default job duration — 60 minutes** (§9.2.1, a genuine
  product decision made in this doc, flagged prominently, not sourced from any PRD/poster
  text). `slotId` is **dropped from the request entirely**, not kept for backward
  compatibility (§9.2.2). `GET .../professionals/{id}/slots?issueId=` is replaced by `GET
  .../professionals/{id}/available-windows?issueId=`, returning derived `AVAILABLE` windows
  already sized to fit the default duration.
- **`users.phone` is new, approved scope (§9.1, DECIDED)**: required at registration for
  `CUSTOMER` accounts (mirrors `defaultAddress`'s exact precedent), exposed to the assigned
  professional starting the moment an order names them (`PENDING` onward) — the same
  access-scoping the service-address snapshot already uses, no new authorization shape.
- **Business timezone finalized as `Asia/Jerusalem`** (§9.5, DECIDED) — a fixed constant,
  not a per-professional/per-region setting.
- The booked-block click-through reuses `OrderTrackingPage.tsx` + the already-existing `GET
  /api/issues/{id}` endpoint almost entirely as-is; small, purely additive frontend changes
  only (no new backend endpoint needed for §16), now including the phone field's display
  (§7.5) since §9.1 resolves the gap the original pass flagged.

---

## 1. Current model (verified against real code, not assumed)

- **`availability_slots`** (`V5`, `data-model.md` §2.5): one row per professional-created
  bookable window (`start_time`, `end_time`, `is_available`). Standard-booking-only —
  `sos_availability` (§2.6) is a structurally separate live on/off toggle, out of scope
  here, untouched.
- **Slot lifecycle** (`api-contract-bookings.md` §3.2/§3.4): claimed (`is_available: true →
  false`) atomically at `POST /api/bookings/orders` time (not at accept time), via a guarded
  `UPDATE ... WHERE is_available = true`; released on `reject`/`cancel`; never released on
  `accept`/`complete`. The **sole** release mechanism is `orders.slot_id` (`V12`) —
  `AvailabilitySlotRepository.releaseSlot(slotId)` is a safe unconditional no-op when
  `slotId IS NULL` (already relied on today for SOS orders, whose `slot_id` is always
  `NULL`). Milestone 7 added guarded `PUT`/`DELETE /api/availability/slots/{slotId}`, both
  gated on the same `is_available = true` protection invariant (`409 SLOT_IN_USE`
  otherwise).
- **No overlap validation ever existed** for a professional's own `availability_slots` rows
  (explicitly out of scope per `api-contract-bookings.md` §2.10) — two overlapping slots for
  the same professional are possible today. Relevant to §6/§9 below: pre-existing data could
  theoretically contain overlapping active orders.
- **`orders`** (`data-model.md` §2.9): already has `booked_start`/`booked_end`
  (`booked_end` nullable — always `NULL` for SOS), `professional_id`, `order_status` (7
  values, `OrderStatus` enum unchanged), `slot_id` (nullable FK → `availability_slots`,
  `ON DELETE SET NULL`), and a full 7-field service-address snapshot. This means the product
  spec's §22 target model (`Order: bookedStart, bookedEnd, professionalId, status`) already
  exists **verbatim** — confirmed by reading `Order.java`/`OrderStatus.java` directly, zero
  schema change needed on the `orders` side except the new exclusion constraint (§6).
- **Professional order-detail/tracking screen**: `frontend/src/features/booking/OrderTrackingPage.tsx`
  (`/orders/:orderId`, shared by both roles, ownership enforced server-side). Currently
  renders: counterparty name (**bug, see §7.3**: always `order.professionalName`, even when
  the viewer *is* the professional — should show `customerName` in that case), status badge,
  live ETA countdown while `ON_THE_WAY` (`useEtaCountdown`, backed by the already-persisted
  `expectedArrivalAt`), `bookedStart` only (not `bookedEnd` — trivial gap, data already
  exists on the DTO), full service-address snapshot, `finalPrice`, and role/state-gated
  actions (cancel / mark-on-the-way / mark-completed / leave-a-review-link) via
  `shared/api/bookings.ts`. It does **not** currently render: order id, category, issue
  description, urgency type, or issue photos — all of that lives on `GET /api/issues/{id}`
  (`api-contract-bookings.md` §2.1), which already has correct authorization for a
  professional ("allowed iff an `orders` row exists with `issue_id = :id AND
  professional_id = <caller>`, any status") and already resolves images via the
  presigned-URL mechanism. `IncomingRequestCard.tsx` (dashboard's pending-request feed) is
  the only other place issue photos are currently rendered on the professional side —
  confirms the pattern (fetch `GET /api/issues/{issueId}` alongside the order, render
  `issue.images[].imageUrl` directly) is already proven in this codebase.
- **Presigned image mechanism**: `storage.service.StorageService#getPresignedUrl(callerId,
  key)` — already the sole mechanism `issues`/`professionals`/`bookings` use; reused
  verbatim, no new code needed for image display in this feature.
- **Current frontend availability UI**: `AvailabilityPage.tsx` renders
  `SosAvailabilityToggle` (untouched by this feature) above `SlotForm.tsx` (create) and
  `SlotList.tsx` (list + inline edit/delete). This whole Standard-slot section is what gets
  replaced by the new calendar (§7).

---

## 2. Final model — new tables

### 2.1 `professional_working_hours` (new)

One row per professional per weekday (Sunday=0 … Saturday=6, matching the product spec's
own Sunday-first example). One default range per weekday — the product does not currently
support multiple ranges per day, and the spec explicitly says that's acceptable ("otherwise
one default range per weekday suffices").

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `professional_id` | `BIGINT` | NO | — | FK → `professionals(id)` `ON DELETE CASCADE` (pure per-professional config, no independent meaning, same convention `sos_availability` already uses). |
| `weekday` | `SMALLINT` | NO | — | `CHECK (weekday BETWEEN 0 AND 6)`. `0 = Sunday … 6 = Saturday`. |
| `enabled` | `BOOLEAN` | NO | `true` | `false` = "not working" that weekday (§3's "Saturday: Not Working" example). |
| `start_time` | `TIME` | YES | `NULL` | Wall-clock local time in the app's business timezone (§5.6 below) — **not** `TIMESTAMPTZ` (this is a recurring weekly rule, not a point in time). `NULL` only valid when `enabled = false`. |
| `end_time` | `TIME` | YES | `NULL` | Same. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Bumped on every `PUT`. |

**Constraints**: PK(`id`); `UNIQUE(professional_id, weekday)`; FK as above; `CHECK
(weekday BETWEEN 0 AND 6)`; `CHECK (enabled = false OR (start_time IS NOT NULL AND
end_time IS NOT NULL AND end_time > start_time))`.
**Indexes**: `idx_professional_working_hours_professional ON (professional_id)` (the only
access pattern — always "give me this professional's whole week").

### 2.2 `professional_availability_blocks` (new)

A manual, temporary exception — personal appointment, lunch, vacation, etc. (§5). Editable/
deletable (§6 of the product spec); never auto-generated, never represents a booking.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `professional_id` | `BIGINT` | NO | — | FK → `professionals(id)` `ON DELETE CASCADE`. |
| `start_at` | `TIMESTAMPTZ` | NO | — | A real point in time (unlike working hours, a block is a one-off dated exception, not a recurring rule). |
| `end_at` | `TIMESTAMPTZ` | NO | — | `CHECK (end_at > start_at)`. |
| `reason` | `VARCHAR(255)` | YES | `NULL` | Optional short free-text reason (§13 — "optionally a short reason field if one already exists in the model," here newly introduced alongside the table itself since no prior reason field existed to reuse). |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Bumped on edit. |

**Constraints**: PK(`id`); FK as above; `CHECK (end_at > start_at)`; **exclusion
constraint** (requires `btree_gist`, see §5's migration list):
```sql
ALTER TABLE professional_availability_blocks
  ADD CONSTRAINT ck_blocks_no_overlap
  EXCLUDE USING gist (professional_id WITH =, tstzrange(start_at, end_at) WITH &&);
```
This is the DB-level half of §37-41's required "overlapping-block validation" test — a
professional cannot have two of their own blocks overlap, enforced authoritatively, not
just application-side.
**Indexes**: `idx_professional_availability_blocks_professional_start ON (professional_id,
start_at)` (calendar-range queries — the GiST index above also serves this, but a plain
btree is cheaper for the common "date range" read path).

### 2.3 `orders` — no new columns, one new constraint

No schema change to any existing column — `booked_start`/`booked_end`/`professional_id`/
`order_status` already exist exactly as the product spec's §22 target model names them. One
new constraint, described in §6 (double-booking protection).

**Usage change, not a schema change (§9.2, decided)**: as of the order-creation rework (M2/
M6, §10), every **newly created** Standard order persists `slot_id = NULL` — the column and
its FK are untouched, but nothing populates it going forward. This is the exact same
already-proven-safe pattern SOS orders have used since Milestone 4
(`AvailabilitySlotRepository.releaseSlot(null)` is already a safe no-op) — see §9.2.2 for
the full reasoning.

### 2.4 What happens to `availability_slots` / its endpoints — explicit, per §23

**Kept, unmodified, not migrated, not dropped.** Reasoning:
- Existing `orders` rows reference `availability_slots` via `slot_id` (`ON DELETE SET
  NULL`) — dropping the table would either orphan that FK or require nulling historical
  data for no benefit. There is no production data pre-launch (per `overview.md`'s backend
  MS9 entry), but the table is also the audit trail for exactly how each historical Standard
  order's time window was originally sourced; no reason to destroy it.
- The four existing endpoints (`POST`/`GET .../slots/me`/`PUT`/`DELETE
  /api/availability/slots/{slotId}`) keep working exactly as today, including their
  `is_available`-guarded booking-protection invariant — **no code change to
  `availability.entity.AvailabilitySlot`/`AvailabilitySlotRepository`/`AvailabilityService`'s
  existing slot methods, no migration.** They simply become unreachable from the new
  professional-facing UI once the frontend milestones replace `AvailabilityPage` (§7) —
  "deprecated by UI removal," not "deprecated by code removal." Once M6 (§10) ships, these
  endpoints become fully vestigial (no code path creates new rows anymore) but are still
  left in place, not deleted — cheap insurance, zero ongoing cost.
- **No data migration is required** — there is nothing to migrate *to*: a
  `professional_working_hours`/`professional_availability_blocks` row cannot be
  meaningfully derived from historical `availability_slots` rows (a slot is a single
  bookable window, not a recurring weekly rule or a blocked-time exception — the two
  concepts don't map onto each other). Every professional simply starts with **no**
  configured working hours until they complete the new first-time setup (§3 of the product
  spec) — this is the expected, spec-described onboarding state, not a migration gap.

### 2.5 `users` — one new column, `phone` (new, §9.1, DECIDED)

Not part of the calendar/availability feature itself — new, approved scope bundled into
this same design pass at the user's direction (§9.1). Listed here because it's the one
other schema change this update introduces, alongside §2.1/§2.2's two new tables.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `phone` | `VARCHAR(20)` | YES | `NULL` | **New column.** Nullable at the DB level (no backfillable value for pre-existing rows, and always `NULL` for a `PROFESSIONAL` row) — required at the API layer for new `CUSTOMER` registrations. Mirrors `default_city`/`default_street`/`default_house_number`'s exact nullability/requiredness split (`V20`, `data-model.md` §2.2) — the closest, most recent precedent for "add a required customer profile field," followed deliberately rather than inventing a different convention. `VARCHAR(20)` matches the length already used for `default_house_number`/`default_apartment` et al. — ample for any Israeli phone number format, with or without a country-code prefix. Read-only after registration — no edit endpoint is added in this design, same as `defaultAddress` (`api-contract.md` §2.4: "no endpoint exists in this API to update the default address"). |

**Constraint/index**: none beyond the column itself — not a lookup/filter path for any query
in this design (read-by-order-party or read-by-self only), same reasoning `data-model.md`
§2.9 already applies to the service-address snapshot columns' own lack of an index.

See §9.1 for the full requiredness rationale and the phone-visibility privacy rule (which
professional may see it, and starting at which order status).

---

## 3. Entity / repository / service / controller shapes

New package-internal classes, all inside the existing `com.pronto.availability` package
(same package the current `AvailabilitySlot`/`SosAvailability` machinery lives in — this
feature is squarely that package's domain, no new top-level package needed):

- `entity.ProfessionalWorkingHours` — `id`, `professionalId` (plain FK column, same
  convention as `AvailabilitySlot`), `weekday` (`int`, `0`-`6`), `enabled` (`boolean`),
  `startTime`/`endTime` (`java.time.LocalTime`, nullable), `createdAt`/`updatedAt`.
- `entity.ProfessionalAvailabilityBlock` — `id`, `professionalId`, `startAt`/`endAt`
  (`java.time.Instant`), `reason` (`String`, nullable), `createdAt`/`updatedAt`.
- `repository.ProfessionalWorkingHoursRepository` — `findByProfessionalId(Long)` (≤7 rows);
  upsert is handled at the service layer (load existing 7-or-fewer rows, update in place or
  insert missing weekdays, inside one `@Transactional` method — a plain JPA
  save-per-weekday loop is sufficient at this row count, no bulk-SQL needed).
- `repository.ProfessionalAvailabilityBlockRepository` — `findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan(...)`
  (date-range query for the calendar derivation and for edit/delete ownership checks),
  standard `findById`/`deleteById`. No manual overlap-check query needed at the repository
  level — the exclusion constraint (§2.2) is the authoritative guard; the service layer
  still does a pre-check `SELECT` for a fast, friendly `409` before attempting the insert
  (see §6 for why both layers exist).
- `service.AvailabilityDerivationService` (**new**) — the core read-side engine, injected
  wherever derived availability is needed (the calendar endpoint, and, per §9.2.2, the
  order-creation validation path and the `available-windows` listing endpoint). One method:
  ```java
  List<CalendarSegment> deriveCalendar(Long professionalId, Instant from, Instant to)
  ```
  `CalendarSegment` is a small record: `type` (`AVAILABLE | BLOCKED | BOOKED`), `startAt`,
  `endAt`, and, only when `type == BLOCKED`, `blockId`/`reason`; only when `type == BOOKED`,
  `orderId`/`orderStatus`. See §5 for the exact algorithm.
- `service.AvailabilityService` (**existing class, extended**) — gains
  `getWorkingHours(callerId)` / `updateWorkingHours(callerId, request)` /
  `createBlock(callerId, request)` / `updateBlock(callerId, blockId, request)` /
  `deleteBlock(callerId, blockId)` / `getCalendar(callerId, from, to)` (delegates to
  `AvailabilityDerivationService` plus a raw `workingHours` read for the response's
  `workingHours` array). Existing slot methods (`create`/`edit`/`delete`/`listMine`/SOS
  toggle) are **untouched** — same class, new methods alongside, not a rewrite.
- `controller.AvailabilityController` (**existing class, extended**) — gains the 6 new
  routes listed in §4. Existing 6 routes (4 slot + 2 SOS) untouched.
- `config.AvailabilityWebConfig` (**no change**) — already registers a blanket
  `RoleRequiredInterceptor(PROFESSIONAL)` on `/api/availability/**`; every new route already
  falls under that wildcard, exactly like Milestone 4's SOS-toggle addition needed zero
  config change. Confirmed by reading the class directly (§1 above).

### 3.1 Additional cross-package touches (from §9.1/§9.2, not new `availability`-package classes)

Not part of the `availability` package's own new-class list above — cross-referenced here
for completeness, full detail lives in §9.1 (phone) and §9.2.2 (order-creation rework):

- Registration DTO (wherever `RegisterRequest`'s `customer` nested object lives, `auth`
  package): new `customer.phone` field, `@NotBlank`, alongside the existing
  `customer.defaultAddress` fields (§9.1).
- `GET /api/users/me` response DTO: new top-level `phone` field, same nullability/placement
  convention as `defaultAddress` (§9.1).
- `bookings.dto.OrderDetailResponse`: new `customerPhone` field, populated by the existing,
  unmodified `getOrderDetail` authorization check — no new authorization code (§9.1).
- `bookings.dto.CreateOrderRequest`: `slotId` removed, `bookedStart` added (§9.2.2).
- `bookings.service.BookingsService`: new constructor dependency on
  `availability.service.AvailabilityDerivationService`; new
  `DEFAULT_JOB_DURATION_MINUTES` constant; reworked `createOrder` validation path (§9.2.2).
  `createSosOrder` — **unchanged**.
- `bookings.controller.BookingsController` / `bookings.config.BookingsWebConfig`: old
  `GET .../professionals/{id}/slots` route and its literal path-pattern entry replaced by
  `GET .../professionals/{id}/available-windows` (§9.2.2).
- `availability.service.AvailabilityDerivationService`: new `deriveAvailableWindows(...)`
  method, a thin filter over `deriveCalendar`'s own output — no duplicated derivation logic
  (§9.2.2).

---

## 4. API design

All new endpoints below live under `/api/availability/*`, `PROFESSIONAL`-only, already
covered by the existing wildcard role gate. No route ever takes a `professionalId`
path/query parameter — the caller's own `professionals.id` is always resolved server-side
from the JWT (same `ProfessionalRepository.findByUserId` mechanism every existing
`availability` endpoint already uses), which structurally satisfies §32 ("a professional may
only view bookings/data assigned to them") for this whole feature with no additional
authorization code needed anywhere in this package.

### 4.1 `GET /api/availability/working-hours`

Returns the caller's configured week (0-7 entries — fewer than 7 only before first-time
setup completes). `200`, empty array on a brand-new professional (frontend renders the
first-time setup flow, §7.2, in that case — not an error).

```json
{
  "workingHours": [
    { "weekday": 0, "enabled": true, "startTime": "08:00", "endTime": "18:00" },
    { "weekday": 5, "enabled": true, "startTime": "08:00", "endTime": "13:00" },
    { "weekday": 6, "enabled": false, "startTime": null, "endTime": null }
  ]
}
```

### 4.2 `PUT /api/availability/working-hours`

Replaces the caller's entire week in one call (idempotent upsert of all 7 weekdays,
transactional). Chosen over 7 separate per-weekday `PUT`s because the product spec frames
this as one coherent settings form (§3), and a partial-week write would leave an
ill-defined state for any weekday the client forgot to send.

**Request**: array of exactly 7 entries, one per weekday `0`-`6`, no duplicates/gaps →
`400 VALIDATION_ERROR` otherwise. Each entry: `{ weekday, enabled, startTime?, endTime? }`
— `startTime`/`endTime` required and `endTime > startTime` when `enabled = true` (`400
VALIDATION_ERROR` otherwise); ignored (may be omitted or `null`) when `enabled = false`.
**Response**: `200`, same shape as §4.1.
**Never touches `professional_availability_blocks` or `orders`** — by construction (this
service method only writes `professional_working_hours` rows), which is exactly what
satisfies §11/§12's "editing working hours must never mutate/delete confirmed bookings or
corrupt manual-block history" — not an explicit guard, a structural guarantee from three
separate tables with no cross-table write path.

### 4.3 `POST /api/availability/blocks`

**Request**: `{ startAt, endAt, reason? }` (ISO-8601 with offset). Validation: parseable,
`endAt > startAt` → `400 VALIDATION_ERROR`; `startAt >= now()` → `400 VALIDATION_ERROR`
(mirrors `availability_slots`' existing "strictly future" convention, relaxed to `>=` since
blocking "the rest of today" is a legitimate use case a strict future-only rule would
awkwardly forbid).

**Behavior**:
1. Resolve caller's `professionals.id`.
2. Pre-check: does `[startAt, endAt)` overlap any of the caller's own existing orders in
   `PENDING`/`CONFIRMED`/`ON_THE_WAY` with a non-null `bookedEnd`? → `409
   BLOCK_OVERLAPS_BOOKING` (a **new judgment call**, not literally specified by the product
   spec, but a direct consequence of §7's "the accepted booking is the source of truth for
   occupied time" — a manual block must not be usable to paper over/hide an existing
   booking). Flagged explicitly here as a judgment call, not silently invented — see §9.3.
3. Insert the row. The exclusion constraint (§2.2) is the authoritative backstop for the
   block-vs-block race (two concurrent block-create calls for the same professional/range —
   low-probability, single-actor race, same risk class as `availability_slots`' historical
   lack of overlap protection, except this table *does* get one, per the product spec's
   explicit test requirement).
4. `23P01` (Postgres exclusion-violation SQLState) on insert → caught, mapped to `409
   BLOCK_OVERLAPS_EXISTING_BLOCK`.
5. Return `201` with the created block.

### 4.4 `PATCH /api/availability/blocks/{blockId}`

Same request/validation shape as §4.3 (full replace of `startAt`/`endAt`/`reason`, not a
partial patch, despite the HTTP verb — mirrors `PUT /api/availability/slots/{slotId}`'s
existing "resend the whole editable shape" convention in this same package). Ownership:
load by id → `404 NOT_FOUND` if missing; `professionalId` mismatch → `403 FORBIDDEN`. Same
overlap checks as create, excluding the block's own row from both the pre-check and (via
`ON CONFLICT`-style exclusion semantics, which naturally exclude the row being updated from
colliding with itself) the exclusion constraint. `200` on success.

### 4.5 `DELETE /api/availability/blocks/{blockId}`

Ownership check identical to §4.4. No "in use" protection needed — unlike a slot, a block is
never referenced by any FK, so deleting it can never orphan or corrupt anything else (§41's
"deleting a manual block cannot delete a booking" safety test is satisfied by construction:
this method only ever issues `DELETE FROM professional_availability_blocks WHERE id = ...
AND professional_id = ...`). `204 No Content` on success.

### 4.6 `GET /api/availability/calendar?from={iso}&to={iso}`

**The one new "big" endpoint — justified against §24's "don't add unnecessarily"
instruction**: without it, the frontend would need three separate calls (`GET
working-hours`, a new blocks-by-range listing, and an orders-by-range listing that doesn't
currently exist either) and would have to re-implement the subtract-and-merge derivation
algorithm in TypeScript — duplicating business logic client-side, with real risk of drifting
from the backend's own double-booking rules over time. A single derived, backend-computed
endpoint is the correct call here specifically because the "actual available time" concept
(§1 of the product spec) is a computed view over three tables, not raw CRUD data — exactly
the case where a consolidated read endpoint earns its keep. No separate "list blocks for
date/week" endpoint is added (§24 suggests one) — the calendar response already carries
every block's `id`/`startAt`/`endAt`/`reason` inline via its `segments` array, so a second,
narrower listing endpoint would be pure duplication with no caller.

**Request**: `from`/`to` query params, ISO-8601 date or datetime, `to > from`, capped at a
6-week span (`400 VALIDATION_ERROR` beyond that — a generous ceiling matching the "day/week
view" use case, not paginated, consistent with this doc family's existing "no pagination at
MVP scale" convention).

**Response `200`:**
```json
{
  "professionalId": 43,
  "from": "2026-08-16T00:00:00+03:00",
  "to": "2026-08-23T00:00:00+03:00",
  "timezone": "Asia/Jerusalem",
  "workingHours": [
    { "weekday": 0, "enabled": true, "startTime": "08:00", "endTime": "18:00" }
  ],
  "segments": [
    { "type": "AVAILABLE", "startAt": "2026-08-16T08:00:00+03:00", "endAt": "2026-08-16T12:00:00+03:00" },
    { "type": "BLOCKED", "startAt": "2026-08-16T12:00:00+03:00", "endAt": "2026-08-16T13:00:00+03:00", "blockId": 12, "reason": "פגישה אישית" },
    { "type": "AVAILABLE", "startAt": "2026-08-16T13:00:00+03:00", "endAt": "2026-08-16T15:00:00+03:00" },
    { "type": "BOOKED", "startAt": "2026-08-16T15:00:00+03:00", "endAt": "2026-08-16T16:30:00+03:00", "orderId": 900, "orderStatus": "CONFIRMED" },
    { "type": "AVAILABLE", "startAt": "2026-08-16T16:30:00+03:00", "endAt": "2026-08-16T18:00:00+03:00" }
  ]
}
```
`workingHours` is date-independent (returned once, not per-day) so the frontend can shade
the "outside working hours" background across the whole visible week without per-day
lookups. Time outside any `enabled` weekday's `[startTime, endTime)` window has **no**
segment at all for that gap — the frontend treats an absence of any segment as "outside
working hours" (§10 — must not behave like an editable manual block; the frontend simply
renders no click affordance there, or a disabled/non-interactive background). A `PENDING`
order (with a real `bookedEnd`) is rendered with `type: "BOOKED"`, `orderStatus: "PENDING"`
— see §9.4 for why this is a judgment call, not literally named as its own state by §9 of
the product spec. `COMPLETED` orders within `[from, to)` (past bookings, §20) are included
identically, `orderStatus: "COMPLETED"` — the frontend applies a distinct, muted visual
treatment per §20's "consider a distinct completed visual state."

**Status codes**: `200` · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` · `403 FORBIDDEN`.

### 4.7 New error codes

| `error.code` | HTTP | Meaning |
|---|---|---|
| `BLOCK_OVERLAPS_EXISTING_BLOCK` | 409 | §4.3/§4.4 — the requested range overlaps another of the caller's own manual blocks. |
| `BLOCK_OVERLAPS_BOOKING` | 409 | §4.3/§4.4 — the requested range overlaps an existing `PENDING`/`CONFIRMED`/`ON_THE_WAY` order with a real `bookedEnd`. |

`BOOKING_TIME_UNAVAILABLE` (the order-creation-side code, `bookings` package, `409`) is
**finalized and added** — see §9.2.2 for its exact semantics and both call sites. Listed
here for cross-reference only, since this doc is where it was first named; it belongs to
`bookings`' error-code taxonomy (`api-contract-bookings.md`), not `availability`'s.

### 4.8 Existing endpoints reused unmodified

- `GET /api/bookings/orders/{orderId}` (`OrderDetailResponse`) — **reused as-is, zero
  backend change.** See §7.3 for why the missing §16 fields (category/description/
  urgency/images) are covered by a second, already-existing call instead of extending this
  DTO.
- `GET /api/issues/{id}` — **reused as-is, zero backend change.** Already returns
  `categoryId`/`categoryCode`/`description`/`urgencyType`/`images` (presigned URLs, fresh
  per call) with correct professional-authorization semantics.
- `POST/PUT/DELETE /api/availability/slots*`, `GET .../slots/me` — reused as-is, unmodified,
  simply no longer linked from the professional-facing UI (§2.4).
- `PUT`/`GET /api/availability/sos-availability` — reused as-is, completely untouched, per
  the task brief's explicit exclusion.
- `POST .../accept` / `.../reject` / `.../cancel` / `.../on-the-way` / `.../complete` — all
  five reused as-is; the booking summary screen's action buttons already call these exact
  functions today, no change needed (§17).

---

## 5. Derivation logic

`AvailabilityDerivationService.deriveCalendar(professionalId, from, to)`:

1. **Business timezone**: a single named constant, `Asia/Jerusalem` (recommended — see
   §9.5, no such constant currently exists anywhere in the codebase; this is a genuine gap
   this feature is the first to need). All wall-clock `TIME` values in
   `professional_working_hours` are interpreted in this zone; the response's `timezone`
   field states it explicitly so the frontend never has to guess or hardcode it separately.
2. Split `[from, to)` into calendar days in that timezone.
3. For each day, look up that weekday's `ProfessionalWorkingHours` row.
   - Missing, or `enabled = false` → the entire day contributes **zero** `AVAILABLE`
     segments (§10 — outside working hours, not editable as a block).
   - Present and `enabled = true` → start with one `AVAILABLE` candidate interval
     `[dayDate + startTime, dayDate + endTime)`, converted to an absolute `Instant` in the
     business timezone (DST-safe via `ZonedDateTime`, not raw offset arithmetic).
4. **Subtract blocks**: fetch every `ProfessionalAvailabilityBlock` overlapping the day
   (`startAt < dayEnd AND endAt > dayStart`), clip each to the day's bounds, and subtract
   from the running `AVAILABLE` interval set — every overlapped sub-range becomes its own
   `BLOCKED` segment (carrying that block's `id`/`reason`), splitting the remaining
   `AVAILABLE` interval(s) as needed. Standard sorted-interval-subtraction, O(blocks-per-day)
   — negligible at this data scale (a professional realistically has single-digit blocks per
   day).
5. **Subtract bookings**: fetch every `orders` row for this professional where
   `order_status IN ('PENDING','CONFIRMED','ON_THE_WAY')` **and** `booked_end IS NOT NULL`
   (this second condition is what structurally excludes every SOS order, which always has
   `booked_end = NULL` — no `urgency_type` lookup needed, the existing nullability
   convention already disambiguates), overlapping the day. Same subtract-and-split
   algorithm as step 4, producing `BOOKED` segments (`orderId`/`orderStatus`).
6. **Historical/`COMPLETED` bookings** (§20): additionally fetch `order_status = 'COMPLETED'`
   orders (with non-null `bookedEnd`) overlapping `[from, to)` and emit them as `BOOKED`
   segments too (`orderStatus: "COMPLETED"`) — these are **not** subtracted from a
   still-open `AVAILABLE` interval (a completed job's original window is definitionally in
   the past relative to "now," so there is no live `AVAILABLE` candidate left to subtract
   from at that point in time regardless).
7. Sort all resulting segments by `startAt` and return.

**Grid precision (§25) — exact boundaries, not rounded.** The derivation always computes
and returns **exact** timestamps from the underlying data — a `10:15`–`11:15` booking
renders as exactly `10:15`–`11:15`, never snapped to `10:00`/`11:30`. The "30-minute grid"
is purely a **frontend rendering/interaction convention**: the calendar's vertical axis
draws gridlines every 30 minutes, and *new* manual-block start/end pickers snap user input
to the grid (§13's create-block interaction) — existing bookings are drawn at their real
pixel-accurate position within that grid regardless of alignment, exactly matching the
product spec's own worked example. This distinction is stated explicitly here because §25's
wording is easy to misread as "round the data."

---

## 6. Double-booking protection design

**Authoritative mechanism: a Postgres partial exclusion constraint directly on `orders`**,
not application-level locking alone — this is the DB-level equivalent of the existing
`availability_slots` atomic-`UPDATE`-guard pattern, adapted to a range-overlap check instead
of a single-row state guard (which the old model didn't need, since one row = one window,
by construction).

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE orders
  ADD CONSTRAINT ck_orders_no_overlap
  EXCLUDE USING gist (
    professional_id WITH =,
    tstzrange(booked_start, booked_end) WITH &&
  )
  WHERE (order_status IN ('PENDING', 'CONFIRMED', 'ON_THE_WAY') AND booked_end IS NOT NULL);
```

- **Scope**: only rows with a non-null `booked_end` participate — this is the existing,
  already-reliable signal that structurally excludes every SOS order (always `booked_end =
  NULL`) from this constraint entirely. SOS orders remain completely invisible to both the
  weekly calendar and this overlap protection — an explicit, accepted scope boundary (see
  §9.6), consistent with "don't touch `sos_availability`/SOS."
- **Statuses included**: `PENDING`/`CONFIRMED`/`ON_THE_WAY` — matches the existing Standard
  slot-claim semantics exactly (a slot is claimed, i.e. unavailable to anyone else, from the
  moment an order is created in `PENDING`, not only once accepted) — continuity with
  existing behavior, not a new invention. `REJECTED`/`CANCELLED`/`EXPIRED`/`COMPLETED` never
  participate (a rejected/cancelled/expired order's time must become bookable again, and a
  completed order is historical, not a live conflict target).
- **Why this doesn't create new conflicts on `accept`/`on-the-way`**: transitioning
  `PENDING → CONFIRMED → ON_THE_WAY` never changes `booked_start`/`booked_end`, and the row
  was already inside the constrained set from the moment it was `PENDING` — so no new
  overlap can be introduced by a later status transition; the constraint is only ever
  actually exercised at `INSERT` time (order creation).
- **Composition with `BookingsService.createOrder`/`createSosOrder`**: `createSosOrder`
  never touches this constraint at all (`booked_end` always `NULL`). `createOrder` (today's
  `slotId`-based Standard path) is **already** protected by the `availability_slots`
  atomic-claim mechanism, so this new constraint is redundant-but-harmless for that path
  today (defense in depth, free — a slot claim already guarantees no two orders share the
  same slot, and a professional's own slots were never guaranteed non-overlapping with each
  other, so this constraint additionally closes that pre-existing gap for free). It is now
  **load-bearing**, not merely redundant, for the direct-`bookedStart`/`bookedEnd` creation
  path built by §9.2.2 (M2/M6, §10) — that path has no `availability_slots` row to
  atomically claim, so the exclusion constraint is the sole authoritative protection, with
  the service layer's `AvailabilityDerivationService`-based pre-check as the fast, friendly
  first line of defense (mirroring exactly how block creation, §4.3, uses both layers
  together).
- **Race-condition handling (§8's explicit requirement)**: the service layer wraps the
  `INSERT` in a try/catch for Postgres's `23P01` (exclusion-violation) SQLState, mapping it
  to a clean domain error (`409 SLOT_UNAVAILABLE` for the legacy, now-vestigial path, `409
  BOOKING_TIME_UNAVAILABLE` for the direct-time creation path, §9.2.2) rather than letting a
  raw `DataIntegrityViolationException` surface as an unhandled `500` — same "catch the
  specific constraint violation, map to a domain error" pattern already used nowhere else in
  this codebase today (every other guard here uses affected-row-count checks, not constraint
  violations) but a well-understood, standard Spring/JDBC pattern, not a novel mechanism.
- **Pre-existing-data risk, explicitly flagged**: because `availability_slots` never
  enforced non-overlap between a professional's own slots (§1), it is theoretically possible
  for pre-existing `orders` rows to already violate this new constraint. `ALTER TABLE ...
  ADD CONSTRAINT` would fail outright if so. Given there is no production data pre-launch
  (per `overview.md`'s backend MS9 entry — QA/dev environments reseed), this is low risk,
  but the migration (§8, `V27`) must be run against a clean/reseeded database, and a
  pre-flight sanity query is included in that migration's comments so `pronto-coding`
  notices immediately if it ever fails in a seeded environment.

---

## 7. Frontend component plan

### 7.1 Route/page replacement

`AvailabilityPage.tsx` (mounted at `/pro/availability`, unchanged route) is **replaced** by
a new page — recommended name `WeeklyAvailabilityPage.tsx` — composed of:
1. `SosAvailabilityToggle` — **rendered verbatim, no changes**, at the top, exactly where it
   renders today (per the product spec's explicit "leave this toggle in place... just
   visually coexisting" instruction).
2. A working-hours summary/edit entry point — see §7.2.
3. `WeeklyCalendarGrid` — see §7.3.

`SlotForm.tsx`/`SlotList.tsx` are removed from this page's render tree. **They are not
deleted from the repo in the milestone that removes them from the UI** (M4, §10) — left as
orphaned-but-compiling files until M6 (§10) ships and makes the underlying endpoints fully
vestigial (§9.2.2), at which point `pronto-coding` may delete them outright — not required
by this design, cheap to leave either way.

### 7.2 Working-hours setup/edit UI

New component, `WorkingHoursForm.tsx` — a 7-row form (one row per weekday, Sunday first per
the product spec's own example), each row: enable/disable toggle, start-time picker,
end-time picker (disabled/hidden when that row is off). Calls `PUT
/api/availability/working-hours` with the full week on save. Two entry points, same
component:
- **First-time setup** (§3): if `GET /api/availability/working-hours` returns an empty/
  incomplete week, `WeeklyAvailabilityPage` renders this form full-page (blocking-ish, but
  skippable — a professional who skips simply sees an all-"outside working hours" calendar
  until they configure it, not a hard gate) instead of the calendar.
- **Later edits** (§11): reachable via a "עריכת שעות עבודה" (edit working hours) button
  above the calendar grid, opening the same form in a modal/drawer (reuse whatever new
  `Modal` primitive M5 introduces, §7.4, §10).

  > **Note (2026-08-19): this intent is now fully implemented as designed.** The M3/M4
  > build initially shipped a temporary inline-expansion stand-in instead (`Modal.tsx`
  > didn't exist yet at that time — see `frontend/src/features/dashboard/README.md`'s
  > M3/M4 section for that historical detail). MS12 — Availability UX Cleanup
  > (`docs/architecture/product-ms12-availability-ux-cleanup-design.md`) replaced the
  > inline stand-in with the real `Modal`-based flow described here and also removed the
  > permanently-visible working-hours summary list. If you're reading this design doc for
  > current behavior, treat MS12 as the superseding source, not this deviation note.

### 7.3 `WeeklyCalendarGrid.tsx`

The Google-Calendar-like weekly view. Consumes `GET /api/availability/calendar?from=&to=`
for the currently-visible week (Sunday-Saturday, matching the working-hours row order).

- **Layout**: 7 day columns × a vertical time axis, fixed default visible range **06:00-
  23:00** (covers every example in the product spec, e.g. Friday 08:00-13:00), with vertical
  scroll beyond that if a professional's working hours ever exceed it (§2, §25's 30-minute
  gridlines).
- **Page width**: `DESIGN_SYSTEM.md` §12's "Normal desktop page" (`max-width: 1200px`), not
  the narrower "focused workflow" width used by booking/checkout forms — the calendar is a
  dashboard view, not a linear form flow.
- **Visual states (§9 of the product spec)**: `AVAILABLE` / `BLOCKED` / `BOOKED`, each with a
  distinct fill **and** a text label/icon (not color-only, per the explicit accessibility
  requirement) — reuse `StatusBadge`'s existing color mapping for `BOOKED` sub-states
  (`PENDING`→"New"/neutral-blue, `CONFIRMED`/`ON_THE_WAY`→primary teal/blue, `COMPLETED`→
  green, per `DESIGN_SYSTEM.md` §56 — **do not invent new colors independently**, the design
  system explicitly warns against this). `BLOCKED` gets its own distinct treatment (e.g. a
  diagonal-hatch pattern + a lock/calendar-block icon, satisfying "use labels/icons/patterns
  too"). Time outside working hours renders as a plain muted/disabled background with no
  click affordance at all (§10).
- **Click behavior (§35-36)**:
  - `AVAILABLE` → opens `CalendarBlockModal.tsx` (create mode), pre-filled with the clicked
    date/time range, calling `POST /api/availability/blocks`.
  - `BLOCKED` → opens the same `CalendarBlockModal.tsx` (edit mode, pre-filled from that
    segment's `blockId`/`startAt`/`endAt`/`reason` — **no extra `GET` needed**, the
    already-fetched calendar response carries everything the modal needs), with an
    additional delete action (`DELETE /api/availability/blocks/{blockId}`).
  - `BOOKED` → **navigates directly to `/orders/{orderId}`** (`OrderTrackingPage`), passing
    the current week's start date via router state
    (`navigate(`/orders/${orderId}`, { state: { returnTo: { weekStart } } })`) so §43's
    "preserve calendar context" requirement is satisfied — `OrderTrackingPage`'s back button
    reads `location.state?.returnTo` when present and, if so, navigates back to
    `/pro/availability?week=${weekStart}` instead of its existing default `backPath` logic
    (which is otherwise unchanged for every other entry point into that screen). **No new
    availability-editing UI is ever reachable from a `BOOKED` click** (§15's critical
    constraint) — the click handler branches on segment `type` before any modal/edit code
    path is even reachable.
- **Concurrency/refresh (§31)**: reuse the existing `usePolling` primitive (already built,
  §1) pointed at `GET /api/availability/calendar` for the currently-visible week, at a
  **coarser interval than the 3-5s order-tracking polling** — recommended 20-30s (a
  judgment call: the calendar isn't a live status-tracking screen the way `OrderTrackingPage`
  is, and a professional accepting their own booking already sees the effect immediately on
  that same action's response, so this polling only needs to catch a second-tab/second-device
  edit or a newly-arrived booking while the calendar tab sits idle).
- **Loading/error states (§30)**: initial load spinner, a retryable error banner on calendar
  fetch failure (reuse the existing `role="alert"` banner pattern used everywhere else in
  this codebase — `AvailabilityPage`/`SlotList`/`OrderTrackingPage` all already do this
  identically), and per-action loading/error states on block create/edit/delete (same
  pattern `SlotForm`/`SlotList` already established, directly reusable).

### 7.4 Mobile/responsive plan (§27-29)

Per `DESIGN_SYSTEM.md` §57 ("On mobile prefer bottom sheets for... Date selection"): below
the existing responsive breakpoint the app already uses elsewhere, `WeeklyCalendarGrid`
switches from a 7-column grid to a **single-day focused view with a day switcher** (not a
horizontally-shrunk 7-column grid, which the product spec explicitly warns against) — a
horizontal day-selector strip (7 tappable day chips) above one day's vertical timeline,
consistent with "day/week switch" being an explicitly acceptable pattern. `CalendarBlockModal`
renders as a bottom sheet on mobile (`border-radius: 20px 20px 0 0` per §13/§57) and a
centered modal on desktop — both variants of one component, not two.

**New shared primitive needed**: no `Modal`/`Drawer`/`BottomSheet` component currently
exists in `frontend/src/shared/components` (confirmed by directory listing). `pronto-coding`
needs to add one small, generic primitive (recommended name `Modal.tsx`, with a `variant:
"dialog" | "sheet"` or an automatic breakpoint-based switch) as part of M5 (§10) — this is a
narrow, justified addition (the design system already documents the exact visual spec for
both variants, §13/§57; this feature is simply the first to need a component implementing
it), not a speculative new abstraction.

### 7.5 `OrderTrackingPage.tsx` extension (§16/§17)

Small, purely additive changes, no new backend endpoint:
1. Alongside the existing `useOrderStatus(orderId)` poll, add a **one-shot** `getIssue(order.issueId)`
   fetch (already exists, `shared/api/issues.ts`) once `order` resolves, to source
   `categoryId`/`categoryCode` (render via the existing `getCategoryNameHe` helper, already
   used by `IncomingRequestCard`), `description`, `urgencyType` (render the existing `SOS`
   tag styling `IncomingRequestCard` already uses), and `images` (render via the existing
   presigned `imageUrl`s, same `<img>` pattern `IncomingRequestCard`'s photo row already
   uses).
2. Render `order.id` and `order.bookedEnd` (both already on the DTO, simply not rendered
   today).
3. **Fix the counterparty-name bug**: show `order.customerName` when `user.role ===
   'PROFESSIONAL'`, `order.professionalName` when `user.role === 'CUSTOMER'` — today it
   always shows `professionalName` regardless of viewer role, which is wrong for a
   professional viewing their own job (§16 requires the summary show *customer* name/detail
   to the professional).
4. Customer **phone**: render `order.customerPhone` when `user.role === 'PROFESSIONAL'` —
   sourced from `OrderDetailResponse.customerPhone` (§9.1), already scoped server-side to
   "the assigned professional, any order status from `PENDING` onward" — the same
   authorization the address snapshot already uses. No client-side gating logic beyond the
   existing role check already needed for the counterparty-name fix (point 3 above): the
   field is server-omitted for anyone not authorized, and `OrderTrackingPage` only ever
   loads an order the caller is already a party to, so by the time this screen can render an
   order for a professional viewer, `customerPhone` is always present. **Not** rendered for a
   `CUSTOMER` viewer — no reciprocal "show me the professional's phone" requirement exists in
   any source document, not built, not asked for.
5. Existing action buttons (cancel/on-the-way/complete/review-link) — **zero change**,
   already exactly match §17's "reuse existing actions, don't duplicate business logic."

### 7.6 `BookingFlowPage` / `SlotPicker.tsx` rework — customer-facing booking flow (M6, §10)

Full design and rationale in §9.2.3 (kept there since it's the direct, forced consequence
of the order-creation rework decided in §9.2) — summarized here only so this section's
component inventory stays complete:

- Fetches `GET /api/bookings/professionals/{id}/available-windows?issueId=` (§9.2.2)
  instead of the retired `GET .../slots?issueId=`.
- `SlotPicker.tsx` (recommend renaming to `StartTimePicker.tsx`) keeps its existing
  date-chip-row + time-chip-grid UI **unchanged** — only the source of the chips changes,
  from discrete pre-made slots to start-time candidates generated client-side (a new pure
  `deriveStartTimeCandidates` helper) from the derived `AVAILABLE` windows, on the same
  30-minute grid this design already establishes (§5/§7.3).
- `onSelect` now yields a chosen ISO `bookedStart` string, not a `slotId`. A client-computed
  `bookedEnd` may be shown for display only — never sent to or trusted from the server; the
  server independently recomputes and validates it (§9.2.2).
- No other step of the booking flow (professional-list step, address entry, confirmation
  step, SOS path) is affected.

---

## 8. Migration list (`V25` onward)

| File | Purpose |
|---|---|
| `V25__create_professional_working_hours.sql` | Creates `professional_working_hours` (§2.1) — new table, purely additive, no risk. |
| `V26__create_professional_availability_blocks.sql` | Creates `professional_availability_blocks` (§2.2), enables `btree_gist`, adds the block/block exclusion constraint — new table, purely additive. |
| `V27__add_orders_no_overlap_constraint.sql` | Adds the `ck_orders_no_overlap` exclusion constraint to the existing `orders` table (§6). **The only migration with real failure risk** — kept in its own file, separate from `V25`/`V26`, specifically so a failure here (pre-existing overlapping data) doesn't also block the two purely-additive new-table migrations from applying. Comment block includes a pre-flight `SELECT` query to detect any existing overlap before the `ALTER TABLE` runs, so a failure is immediately diagnosable rather than a bare constraint-violation stack trace. |
| `V28__alter_users_add_phone.sql` | Adds `users.phone` (§2.5/§9.1) — new nullable column, purely additive, no risk. Mirrors `V20__alter_users_add_default_address.sql`'s exact pattern: nullable at the DB level, enforced required at the API layer only for new `CUSTOMER` registrations going forward. Placed last in this sequence — a separate feature (phone/privacy) bundled into this same design pass at the user's direction, not because it has any dependency on `V25`-`V27`; single responsibility per file, same convention this whole list already follows. |

**No migration touches `availability_slots`, `sos_availability`, or any existing `orders`
column** — confirmed nothing in this design requires it (§2.4/§2.3). **No data migration is
needed or performed** — every professional starts with zero configured working hours (the
expected, spec-described first-time-setup state, §2.4's last bullet), and existing
`availability_slots`/`orders` rows are read (for the derivation) but never written to by any
new code path in M1/M3-M5.

**The order-creation rework (§9.2, DECIDED and built as M2/M6) needs no further migration
beyond `V28`** — confirmed already in the original pass and unchanged by this update:
`orders.booked_start`/`booked_end`/`slot_id` are already nullable/present exactly as
required; only DTO/service/frontend code changes (§9.2.2). `V28` (`users.phone`, §2.5) is
the only schema addition this update introduces beyond the original `V25`-`V27` set.

---

## 9. Decisions (resolved 2026-08-18) and residual judgment calls

**Three of the seven items below (9.1, 9.2, 9.5) were explicit blockers in the original
design pass — all three are now resolved by explicit user decision, recorded in place below,
kept at the same subsection numbers so every cross-reference elsewhere in this doc stays
valid.** The remaining four (9.3, 9.4, 9.6, 9.7) were always independent, non-gating
judgment calls, unaffected by this update — kept for completeness, unchanged.

### 9.1 `users.phone` — DECIDED (user approval, 2026-08-18), new scope

**Approved as new scope.** `users` gains a `phone` column (§2.5); `CUSTOMER` registration
gains a `customer.phone` field; a new privacy rule governs when a professional may see it.
This closes the gap the original pass flagged (§16/§34 of the product spec assume a phone
field and privacy-rule precedent that did not exist in this codebase at the time).

**Column shape and requiredness — mirrors `defaultAddress`'s exact precedent
(`api-contract.md` §2.1/§2.4, `data-model.md` §2.2, `V20`).** `phone` is `VARCHAR(20)`,
nullable at the DB level (no backfillable value for pre-existing rows — this project has no
production data pre-launch, per `overview.md`'s backend MS9 entry — and always `NULL` for a
`PROFESSIONAL` row, exactly like `default_city` et al.), but **required at the API layer for
new `CUSTOMER` registrations** (`@NotBlank` on `RegisterRequest.customer.phone`, same
validation tier as `customer.defaultAddress.city`/`.street`/`.houseNumber`). **Read-only
after registration, same as `defaultAddress`** — no `PUT`/edit endpoint is added in this
design; a self-service phone-edit endpoint is a separate, later feature if ever needed, not
built here (this is the closest, most recent precedent for "add a required customer profile
field," and diverging from it without cause would be an unjustified inconsistency —
`api-contract.md` §2.4 explicitly notes "no endpoint exists in this API to update the
default address"). `phone` is **not** collected for `PROFESSIONAL` registration in this
design — nothing in the task brief or any source document asks for a professional-facing
phone-contact feature (the only stated requirement is the customer's phone becoming visible
to their assigned professional), so none is invented here, per "don't design product
requirements the documents don't support."

**Visibility rule — mirrors the service-address snapshot's exact access-scoping pattern, not
a new authorization shape.** `OrderDetailResponse` (`GET /api/bookings/orders/{orderId}`,
`api-contract-bookings.md` §2.8) gains a `customerPhone` field, populated by
`BookingsService#getOrderDetail`'s **existing, unmodified** authorization check (verified
directly against the real code, `BookingsService.java`, the `getOrderDetail` method): a
caller is authorized to see an order's full detail — including its `service_*` address
snapshot today, and `customerPhone` from this point on — **iff they are the order's own
customer, or the professional named on `order.professionalId`, with no additional
`order_status` filter of any kind.** That check has never gated any field by status; it is
purely "are you a party to this order." Since the service-address snapshot is visible to the
assigned professional starting the moment the order is created in `PENDING` (before any
accept/reject decision — confirmed by reading the check: there is no `order.status IN (...)`
condition anywhere in it), the literal, exact mirror of that pattern requires `customerPhone`
to become visible at the same point: **`PENDING` (i.e. "assignment" — the moment
`order.professionalId` names that professional), not gated further to `CONFIRMED`.**

This resolves the ambiguity the original design flagged ("assignment" vs. "confirmation") in
favor of **assignment (`PENDING`)**, for three concrete reasons, all anchored to the actual
code rather than a preference:
1. **It is the only reading that is a true mirror, not an approximation.** The task's own
   instruction was to reuse the address snapshot's access-scoping "rather than inventing a
   new authorization shape." The address check has zero status-gating; adding a
   `CONFIRMED`-only gate for phone specifically would be a **new, bespoke authorization
   branch** that doesn't exist anywhere else in this codebase's "party-to-order" model —
   itself a small instance of the "new authorization shape" the task says not to invent.
2. **Product consistency with what a `PENDING`-stage professional already sees.** A
   professional deciding whether to accept a still-`PENDING` request already has the full
   service address (above), the issue description/category/urgency, and photos (via `GET
   /api/issues/{id}`, `api-contract-bookings.md` §2.1, itself authorized for "any status" the
   professional is party to). Withholding only the phone number until `CONFIRMED` would be an
   arbitrary, inconsistent carve-out with no analogue anywhere else in this design.
3. **No new code path.** Because the existing `getOrderDetail` authorization check already
   has exactly the right shape (party-to-order, any status), `customerPhone` is populated by
   the same method with no new branch — `phone` is simply read off the loaded `User` row
   (`userRepository.findById(order.getCustomerId())`, already fetched in that method for
   `customerName`) and included in the response unconditionally once the existing
   authorization check has passed. This is the cheapest, lowest-risk implementation, and
   exactly what "mirror the pattern" should produce.

`customerPhone` is added to `OrderDetailResponse` only (the DTO `OrderTrackingPage.tsx`
consumes for the booking-summary screen, §7.5) — **not** to the lean `OrderSummaryResponse`
(`GET /api/bookings/orders/me`'s list entries, deliberately kept lean per that DTO's own doc
comment) and **not** to `OrderResponse` (the create/accept/reject echo response) — the
address snapshot happens to already be present on `OrderResponse` too, but phone has no
stated use case there (no screen renders a phone number from an accept/reject response
body); adding it would be speculative. `pronto-coding` may add it there later for full
symmetry with the address fields if a concrete need arises — not required by this design.

`GET /api/users/me` (`api-contract.md` §2.4) also gains a top-level `phone` field, mirroring
`defaultAddress`'s exact placement/nullability convention there (`null` for `PROFESSIONAL`,
populated for a `CUSTOMER` with a recorded phone) — this is the customer viewing **their
own** phone on their own account screen, an entirely separate, ungated concern from the
order-based professional-visibility rule above (a user can always see their own data).

### 9.2 Standard-order-creation replacement — DECIDED (user decision, 2026-08-18): Option 2 built

The product spec's own premise (§1, §4, §22) is that professionals stop manually creating
individual bookable windows. But `POST /api/bookings/orders` (Standard order creation) and
its slot-listing predecessor (`GET .../professionals/{id}/slots?issueId=`) were **entirely**
built around a customer picking a real `availability_slots` row (`slotId` in the request
body) that a professional pre-created — a genuine, structural consequence of the brief the
product spec's text never addressed, originally flagged here rather than silently resolved.
**The user has now chosen Option 2** of the three originally presented (kept below for
record, annotated):

1. **(NOT CHOSEN) Do nothing to order creation; leave `availability_slots`-based Standard
   booking as the permanent mechanism, and don't actually retire the slot-creation UI.**
   Contradicted §2's explicit "Replace the current availability screen" and §4's "no manual
   slot creation needed" — would have shipped this feature as an additive *view* alongside
   the old slot-creation flow, not a replacement.
2. **(CHOSEN — see §9.2.1/§9.2.2/§9.2.3 for the finalized, built shape) Extend `POST
   /api/bookings/orders` to accept a direct `bookedStart`** (validated against
   `AvailabilityDerivationService` at request time, authoritatively protected by the same
   `ck_orders_no_overlap` exclusion constraint §6 already builds), and change `GET
   .../professionals/{id}/slots?issueId=` to return derived `AVAILABLE` windows instead of
   discrete slot rows.
3. **(NOT CHOSEN) Scope this entire redesign to professional-side visibility/blocking/
   booked-click-through only**, leaving Standard-slot creation reachable and in active use
   alongside the new calendar as a 4th visual overlay category. Most conservative, but
   doesn't fulfill §2/§4's stated goal and adds visual complexity the product spec never
   asked for.

Retiring `SlotForm`'s slot-creation UI at M4 (§10) — already the plan in the original design
pass — is **unchanged by this decision**; only the gating language that previously blocked
Option 2's implementation is removed.

#### 9.2.1 Job duration — DECIDED: fixed default duration, 60 minutes

**Read this before implementing anything that touches order creation.**

**Decision (user, 2026-08-18): the customer picks only a start time; the system derives the
end time by adding a fixed default duration.** The "customer picks both start and end"
sub-option is explicitly ruled out.

> **The exact duration — 60 minutes — is a genuine product decision made in this design
> document, without direct source-document backing. Flagged prominently here, not buried,**
> because there is real business risk in getting this number wrong (too short
> under-represents real job length and creates scheduling pressure adjacent to
> double-booking; too long needlessly shrinks how many start times a professional can offer
> per day for no stated reason), and no PRD/poster/OnePage text specifies it.

**Independent verification performed** (checked directly, not assumed absent, per explicit
instruction): `availability_slots`'s schema (`data-model.md` §2.5) and `CreateSlotRequest`
carry only raw `start_time`/`end_time`, professional-chosen per slot — no duration field or
implied default anywhere. Grepped every `docs/architecture/*.md` file for
"hour"/"minute"/"duration" — the only hits are: (a) this same design doc's own
already-flagged open-question text (self-referential, not a source); (b) the unrelated
`PENDING`-timeout constants (`STANDARD_PENDING_TIMEOUT = 15 min`, `SOS_PENDING_TIMEOUT = 5
min` — `api-contract-notifications.md`, `data-model.md` §3 item 8), which bound how long a
request may sit unanswered, a different concept from how long a job itself takes; (c) k6
performance-test JSON artifacts (`http_req_duration` etc.) — unrelated load-test metrics, not
product data; and (d) illustrative example timestamps in existing docs (e.g.
`api-contract-bookings.md`'s worked example `09:00`-`11:00`, this doc's own §5/§36 worked
example `15:00`-`16:30`) — both are **pre-existing, professional-chosen `availability_slots`
window lengths used only to illustrate JSON shape**, not evidence of any product-mandated
standard duration; treating either as a hidden convention would be over-reading an arbitrary
illustrative number. **Confirmed: no duration convention exists anywhere in this codebase or
its design docs** — this is a from-scratch decision, made here.

**Chosen value and full rationale (verbatim record, for `pronto-lead`'s own report):**

> **60 minutes.** Rationale: (1) It is a plausible "typical single visit" length across the
> fixed 8-category service list (Plumbing, Electrical, AC/HVAC, Appliance Repair, Locksmith,
> Carpentry, Painting, General Handyman) — long enough to not be almost-always-too-short for
> a real diagnosis-plus-straightforward-repair visit (unlike, say, 15-30 minutes, which would
> fit only the fastest jobs like a simple lockout), short enough to not needlessly over-block
> a professional's calendar the way a longer default (e.g. 120 minutes) would — a 120-minute
> default would roughly halve the number of displayable start-time candidates per working day
> compared to 60 minutes, for no product-stated reason. (2) It is a clean multiple of this
> design's own already-established 30-minute grid convention (§5's "grid precision" rule, and
> `WeeklyCalendarGrid`'s 30-minute gridlines, §7.3) — exactly two grid units, so generated
> start-time candidates (§9.2.3) land on the same visual grid the professional's own calendar
> already uses, with no fractional-grid awkwardness. (3) It is a single, named,
> trivially-changeable constant (`BookingsService.DEFAULT_JOB_DURATION_MINUTES = 60`), not a
> schema value — changing it later needs no migration, matching the exact "explicitly flagged
> as a placeholder business figure" treatment this same codebase already gives
> `SOS_SURCHARGE_AMOUNT` (`data-model.md` §2.9) for an analogous made-up-for-MVP business
> figure with no source-document backing; `pronto-coding` must carry the same
> Javadoc-flagged-placeholder comment on this constant. (4) A category-specific or
> professional-configurable duration was considered and explicitly **not** chosen — more
> accurate, but unsupported by any part of this task's scope, and adds real UI/schema surface
> (a per-category or per-professional duration setting) that no source document asks for —
> flagged here as a clearly-labeled future extension, not built now, consistent with "don't
> over-engineer."

#### 9.2.2 Finalized API shapes

**`CreateOrderRequest` (`POST /api/bookings/orders`), new shape:**
```json
{
  "issueId": 101,
  "professionalId": 43,
  "bookedStart": "2026-08-20T09:00:00+03:00",
  "serviceCity": "תל אביב",
  "serviceStreet": "אלנבי",
  "serviceHouseNumber": "12"
}
```
(`serviceApartment`/`serviceFloor`/`serviceEntrance`/`serviceAddressNotes` unchanged,
optional, omitted above for brevity.)

- `bookedStart`: `@NotNull Instant`, required. Server validates it is strictly in the future
  (`> now()`), matching the retired slot-claim path's own `start_time > now()` convention —
  no behavior change to this specific rule, just re-anchored to a client-chosen instant
  instead of a pre-existing row.
- `bookedEnd` is **never accepted from the client** — always computed server-side as
  `bookedStart + DEFAULT_JOB_DURATION_MINUTES` (§9.2.1). A client-supplied `bookedEnd` would
  let a malicious/buggy client request an arbitrary-length booking, silently bypassing the
  fixed-duration decision — not accepted, not even as an optional override.
- **`slotId` is dropped from the request entirely** — not kept, even as an optional/ignored
  field, for backward compatibility. Reasoning: (a) there is no production data and no
  external API consumer to preserve compatibility for (single frontend, redeployed
  atomically with the backend — `overview.md` MS9's "no production data pre-launch" note
  applies here too); (b) once M4/M6 (§10) ship, no UI path can ever populate a `slotId`
  again — `SlotForm`'s slot-creation UI is removed from the professional-facing calendar
  (M4) and the customer's booking-flow slot-picker is reworked to send `bookedStart` (M6) —
  keeping the field would be permanently-dead request surface with a `@NotNull @Positive`
  constraint that can never be satisfied by any real caller once both frontends ship; (c)
  the *storage* column `orders.slot_id` and the *professional's own* `availability_slots`
  CRUD endpoints are kept per §2.4's already-decided reasoning (audit trail, historical
  rows, cheap insurance) — that decision is about **existing data and an unrelated
  professional-facing endpoint family**, not this customer-facing request DTO, so it does
  not argue for keeping `slotId` here too. New orders simply always persist `slot_id = NULL`
  from this point on — the exact same already-proven-safe no-op pattern SOS orders have used
  since Milestone 4.

**`BookingsService.createOrder` — new validation path** (replaces the old §2.4 steps 9-11 of
`api-contract-bookings.md`; steps 1-8 — role/issue-ownership/urgency/bookable/professional/
category checks — are **unchanged**):
1. Compute `bookedEnd = bookedStart.plus(Duration.ofMinutes(DEFAULT_JOB_DURATION_MINUTES))`.
2. **Fast pre-check** (new injected dependency, `AvailabilityDerivationService` — new
   inter-package dependency, `bookings` → `availability`, justified below): derive the
   professional's calendar for just `[bookedStart, bookedEnd)` and confirm it is **fully
   contained within a single derived `AVAILABLE` segment** (inside working hours, not
   overlapping any block, not overlapping any existing `PENDING`/`CONFIRMED`/`ON_THE_WAY`
   booking). Fails → `409 BOOKING_TIME_UNAVAILABLE`, roll back, return immediately — the
   direct functional replacement for the old atomic `UPDATE availability_slots ... WHERE
   is_available = true` guard's "affected rows = 0" branch (old step 9), implemented as a
   `SELECT`-based derivation call instead of an `UPDATE` guard, since there is no more
   discrete row to atomically flip.
3. Atomically transition the issue `OPEN → BOOKED` — **unchanged**, same guard/rollback/
   `409 ISSUE_NOT_BOOKABLE` semantics as the old step 10.
4. Insert the `orders` row: `slot_id = NULL` (always, for every order created via this path
   from now on), `booked_start = bookedStart`, `booked_end = bookedEnd`, rest unchanged from
   the old step 11.
5. **The `INSERT` itself is protected by `ck_orders_no_overlap`** (§6) — now the **sole
   authoritative backstop** for the true concurrency race (two simultaneous `createOrder`
   calls for the same professional with overlapping ranges, both passing step 2's pre-check
   before either commits) — step 2 is a friendly first line of defense only, exactly
   mirroring how block creation (§4.3) already uses both layers together. Catch Postgres
   `23P01` (exclusion violation) on insert → map to `409 BOOKING_TIME_UNAVAILABLE` (same code
   as step 2's failure — a client cannot distinguish "you lost a very fast race" from "that
   time was already gone by the time you asked," and doesn't need to; both mean "pick a
   different time"). Roll back the whole transaction (the issue transition from step 3 rolls
   back automatically, same single-`@Transactional`-method mechanism already used everywhere
   else in this doc family).
6. Commit. Return `201`, same `OrderResponse` shape as before (`bookedEnd` was already a
   field on that DTO, simply always non-null now for a Standard order).

**`BOOKING_TIME_UNAVAILABLE` — finalized, no longer a placeholder name**: `409`, returned by
both step 2 (pre-check) and step 5 (race backstop) above. Added to the existing error-code
taxonomy (`api-contract-bookings.md` §2's Milestone 3 additions table), alongside (not
replacing) `SLOT_UNAVAILABLE`, which becomes **vestigial** (still a valid `ErrorCode` enum
value, never returned by any code path after M6 ships, since nothing can supply a `slotId`
to trigger it anymore) — left in the enum, not deleted, same "cheap insurance" treatment this
doc family already gives orphaned-but-harmless artifacts (§2.4).

**New inter-package dependency justified**: `bookings.service.BookingsService` gaining a
constructor dependency on `availability.service.AvailabilityDerivationService` is a new
cross-package coupling that didn't exist before. Justified because the alternative —
`bookings` re-implementing its own "is this professional free right now" derivation query —
would duplicate the exact subtract-and-merge algorithm §5 already built and risk drifting
from the calendar's own notion of availability over time (the same duplication risk §4.6
already used to justify the calendar endpoint's own existence). One shared derivation engine,
two callers (the calendar read endpoint and now order creation), is the correct shape, not
two independent implementations of the same business rule.

**`GET /api/bookings/professionals/{professionalId}/slots?issueId=` — replaced, not kept for
backward compatibility, new route:**

`GET /api/bookings/professionals/{professionalId}/available-windows?issueId={id}`

Renamed (not merely reshaped at the old path) because keeping the literal string `slots` in
a URL that no longer returns discrete slot rows would be actively misleading for the lifetime
of this API, and there is exactly one client (this project's own frontend, redeployed
atomically with the backend) — no external/versioned-API compatibility concern applies.
`pronto-coding` should treat the old route as fully removed (both the controller method and
`BookingsWebConfig`'s literal-path-list entry for it, `api-contract-bookings.md` §0.1),
replaced by the new one, in the same deploy as M6 (§10).

Auth/role/validation steps 1-5 are **identical** to the old §2.3 (`api-contract-bookings.md`)
— caller role, issue ownership, urgency-type match, bookable-status check, professional
existence, category match — only the final query (old step 6) changes:

- Old step 6 (`SELECT ... FROM availability_slots WHERE ...`) is replaced by a call to
  `AvailabilityDerivationService.deriveAvailableWindows(professionalId, from, to,
  Duration.ofMinutes(DEFAULT_JOB_DURATION_MINUTES))` — a new, narrow method alongside
  `deriveCalendar` (§5), implemented as a thin filter over `deriveCalendar`'s own `AVAILABLE`
  segments (`segment.duration() >= minDuration`) — **no duplicate derivation logic**, reuses
  the exact same subtract-blocks/subtract-bookings algorithm §5 already specifies.
- `from`/`to`: **new secondary judgment call, not specified by any source document** — since
  availability is now derived on demand rather than read from however-far-ahead a
  professional bothered to pre-create slots, an unbounded future window is computationally
  unreasonable. No `from`/`to` query params are exposed on this endpoint (simpler for the
  customer booking flow); the server applies a fixed internal lookahead —
  **recommended `from = now()`, `to = now() + 14 days`** — a generous but bounded near-term
  booking horizon, cheap to derive, trivially adjustable later (an application constant, not
  a schema value). Lower-stakes than the job-duration decision, since it only affects "how
  far ahead can a customer see open times," not the correctness of any booking itself.

**Response `200`:**
```json
{
  "professionalId": 43,
  "issueId": 101,
  "defaultDurationMinutes": 60,
  "timezone": "Asia/Jerusalem",
  "windows": [
    { "startAt": "2026-08-20T08:00:00+03:00", "endAt": "2026-08-20T12:00:00+03:00" },
    { "startAt": "2026-08-20T13:00:00+03:00", "endAt": "2026-08-20T15:00:00+03:00" }
  ]
}
```
Every returned window's duration is `>= defaultDurationMinutes` — windows shorter than that
(e.g. a 20-minute gap between a block and a booking, when the default duration is 60 minutes)
are **dropped entirely** by the derivation filter, never returned as an unusable ghost entry
the frontend would have to separately reject. An empty `windows` array is a valid, expected
response (unchanged semantics from the old "empty `slots` array" case) — not an error.
`defaultDurationMinutes` is echoed in the response (not hardcoded client-side) so the
frontend never needs its own copy of this constant — the same single-source-of-truth
reasoning `timezone` already uses on the calendar endpoint (§4.6).

**Status codes**: unchanged from the old §2.3 (`200` · `400 VALIDATION_ERROR` · `400
CATEGORY_MISMATCH` · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND` · `409
ISSUE_URGENCY_MISMATCH` · `409 ISSUE_NOT_BOOKABLE`).

**`BookingsWebConfig` role-gating**: the `CUSTOMER`-scoped interceptor's literal-path list
(`api-contract-bookings.md` §0.1) must swap `"/api/bookings/professionals/*/slots"` for
`"/api/bookings/professionals/*/available-windows"` — same single-role registration, just a
renamed literal pattern.

#### 9.2.3 Customer-facing frontend impact — `BookingFlowPage` / `SlotPicker.tsx`

**Current interaction model (verified against the real component,
`frontend/src/features/booking/SlotPicker.tsx`)**: consumes a flat, already-future/
already-available `AvailabilitySlotItem[]` (each item a discrete `{ slotId, startTime,
endTime }`), groups them client-side by calendar day into a horizontal date-chip row, and
renders each slot as its own clickable time chip (`formatTimeLabel(slot.startTime)`);
`onSelect(slot)` hands the whole `AvailabilitySlotItem` back to the parent, which reads
`slot.slotId` for the eventual `POST /api/bookings/orders` call.

**This model maps cleanly onto the new shape with a small, well-contained change — not a
different interaction paradigm.** The date-chip-row-plus-time-chip-grid UI is unchanged; only
where the individual chips come from changes:

- Replace the fetched type: `AvailabilitySlotItem[]` → `AvailableWindow[]` (`{ startAt,
  endAt }`, from the new `available-windows` response), plus the response's own
  `defaultDurationMinutes` passed down as a prop/context value (never re-derived or
  hardcoded client-side — same "server is the single source of truth for this constant"
  reasoning as §9.2.2).
- **New pure utility function** (recommended location: `shared/utils/availability.ts`,
  alongside the existing `formatDateTime.ts` helpers this same component already imports),
  e.g. `deriveStartTimeCandidates(windows: AvailableWindow[], defaultDurationMinutes: number,
  gridMinutes = 30): string[]` — for each window, enumerate every `gridMinutes`-aligned
  instant from `window.startAt` up to and including `window.endAt - defaultDurationMinutes`,
  exactly mirroring this same design's already-established 30-minute grid convention (§5/
  §7.3 — reused, not a new number invented for this sub-feature). Pure, unit-testable, no
  component/JSX coupling — same shape as this codebase's existing small date/time helpers.
- Grouping-by-day logic (`dateKey`, the date-chip row) is **unchanged** — it already operates
  on any array of ISO timestamps; simply feed it the flattened list of generated start-time
  candidate strings instead of `slots.map(s => s.startTime)`. Multiple same-day windows (e.g.
  a lunch-hour block splitting 08:00-18:00 into 08:00-12:00 and 13:00-18:00) naturally
  produce two separate runs of candidates that flatten into one day's chip list with no
  special-case code — exactly the existing grouping step's job today.
- `onSelect` callback signature changes from `(slot: AvailabilitySlotItem) => void` to
  `(bookedStart: string) => void` — the parent (`BookingFlowPage`) stores the chosen ISO
  string directly and sends it as `CreateOrderRequest.bookedStart` (§9.2.2). A **client-side
  computed `bookedEnd` may be shown for display only** (`bookedStart +
  defaultDurationMinutes`, purely presentational, e.g. "09:00–10:00" on the confirmation
  step) — **never sent to the server and never trusted as authoritative**; the server
  independently recomputes and validates `bookedEnd` itself (§9.2.2 step 1), so a stale/
  tampered client value can never affect what actually gets booked.
- Empty-state copy/behavior unchanged (triggered by an empty `windows` array, same UX as
  today's empty `slots` array).
- **Naming**: recommend renaming the component `SlotPicker.tsx` → `StartTimePicker.tsx` (and
  the CSS module alongside it) since "slot" no longer describes what's being picked — a
  low-stakes call, either name is acceptable, but the rename avoids permanently misleading
  terminology in a component this design otherwise keeps almost entirely intact.

No other part of `BookingFlowPage`'s flow (professional-list step, confirmation step,
address-entry step) is affected — this change is scoped entirely to the one picker step and
the final `POST` payload's shape.

### 9.3 Block-vs-booking overlap rejection (§4.3 step 2) is a judgment call, not literally specified

The product spec never explicitly states whether creating a manual block may overlap an
existing `PENDING`/`CONFIRMED`/`ON_THE_WAY` booking. Rejecting it (as designed here) is the
reading most consistent with §7's "the accepted booking is the source of truth for occupied
time... do not duplicate booking data into the calendar as independent truth" — but it's an
inference, not a literal instruction, and worth `pronto-lead` confirming.

### 9.4 Should a `PENDING` order render as `BOOKED` on the calendar?

§18's mapping table explicitly starts from `CONFIRMED` and says `PENDING` is "not
necessarily blocking unless existing rules say so." The *existing* rule (§1/§3.4 of this
doc) is that a `PENDING` Standard order **does** already claim the slot/time exclusively —
so, for consistency and to avoid a professional being shown a false `AVAILABLE` slot that's
actually tentatively held, this design renders `PENDING` orders as `BOOKED` (sub-labeled by
`orderStatus`) rather than inventing a 4th top-level visual state beyond §9's three. Flagged
as a judgment call, not a literal instruction — a defensible alternative is a 4th, distinct
"pending/tentative" visual treatment, not designed here since §9 explicitly says "at least
three states," not four.

### 9.5 Business timezone — DECIDED (user confirmation, 2026-08-18): `Asia/Jerusalem`

**Finalized as a settled constant, not a recommendation.** Checked: `data-model.md` §0 only
says "`TIMESTAMPTZ` everywhere... infra likely runs UTC" — no single named business-timezone
constant existed anywhere in the codebase before this feature, because no prior feature
needed to reason about *wall-clock recurring* time (every existing `TIMESTAMPTZ` column is a
point-in-time value, not a weekly rule). **`Asia/Jerusalem`** is the single named
business-timezone constant (a Spring `@ConfigurationProperty` or a shared constant, as
originally proposed), reused by `AvailabilityDerivationService` (§5), the order-creation
duration/validation logic (§9.2.2), and any frontend date math — not hardcoded
independently in multiple places. No further sign-off needed — `pronto-coding` should treat
this as fixed by design, not a per-professional/per-region setting (out of scope, no source
document suggests multi-region support).

### 9.6 SOS orders are entirely invisible to the new calendar — accepted, not fixed

A professional who is mid-`ON_THE_WAY` on an SOS job will show as fully `AVAILABLE` on their
Standard weekly calendar for that same time window, since SOS orders (always `booked_end =
NULL`) never produce a `BOOKED` segment or participate in the overlap constraint (§6). This
is a direct, structural consequence of "don't touch `sos_availability`" and of SOS orders
having no fixed end time to render — not fixable without inventing an assumed SOS-job
duration, which no source document provides. Flagged as an accepted MVP gap, consistent with
the task brief's explicit SOS exclusion, not silently swept under the rug.

### 9.7 Migration risk detail already covered in §6/§8 — not repeated here.

---

## 10. Milestone breakdown for `pronto-coding`

**All decisions are final (§9) — this entire sequence (M1-M6) is one continuous, ungated
plan, nothing waits on further sign-off.** Backend milestones (M1-M2) ship fully before any
frontend milestone starts (M3-M6), per the task's instruction. Each milestone is
independently shippable/testable; dependency notes under each one call out where it
structurally requires an earlier milestone to be done first.

### M1 — Backend: calendar schema, domain, and derivation

**Scope**: `V25`/`V26`/`V27` migrations (§8); `ProfessionalWorkingHours`/
`ProfessionalAvailabilityBlock` entities/repositories; `AvailabilityDerivationService`
(`deriveCalendar`, §5); `AvailabilityService` extensions (working-hours get/put, block
create/edit/delete, calendar read); `AvailabilityController` extensions (§4.1-§4.6); two new
`ErrorCode` values (§4.7, `BLOCK_OVERLAPS_*`). **No frontend work, no change to any existing
booking/order endpoint's behavior, no `phone` field yet (that's M2).**

**Depends on**: nothing — first milestone.

**Acceptance criteria**:
- All 6 new endpoints live-QA'd against a real Postgres instance: working-hours round-trip
  (save/read, disabled-day handling), block create/edit/delete including both overlap
  rejections (§4.3/§4.4), calendar endpoint against the exact worked example in §36 of the
  product spec (Monday 08:00-18:00, block 12:00-13:00, booking 15:00-16:30 → the 5-segment
  result stated there).
- `ck_orders_no_overlap` proven to reject a manually-inserted overlapping test row (both via
  direct SQL and via a concurrent-request simulation if practical).
- Full regression pass on `bookings`/`availability`'s existing endpoints — zero behavior
  change to anything already shipped.
- `docs/architecture/data-model.md` §1/§2 and `availability/README.md` updated with the two
  new tables (per the shared "every package doc must reflect new tables" rule).

### M2 — Backend: order-creation rework, `phone` field, availability-listing replacement

**Scope**:
- `V28` migration (§8, `users.phone`, §2.5/§9.1).
- `AvailabilityDerivationService` gains `deriveAvailableWindows(...)` (§9.2.2), a thin filter
  over `deriveCalendar`'s own output.
- `BookingsService.createOrder` reworked per §9.2.2's exact validation path
  (`DEFAULT_JOB_DURATION_MINUTES` constant with its placeholder-comment treatment per §9.2.1,
  server-derived `bookedEnd`, `AvailabilityDerivationService` pre-check, `ck_orders_no_overlap`
  race backstop, `BOOKING_TIME_UNAVAILABLE` error mapping). `createSosOrder` — **unchanged**,
  confirmed out of scope (§6/§9.2.2).
- `CreateOrderRequest` reshaped (`slotId` removed, `bookedStart` added, §9.2.2).
- `GET /api/bookings/professionals/{id}/slots?issueId=` **replaced** by `GET
  /api/bookings/professionals/{id}/available-windows?issueId=` (§9.2.2) — old route and
  controller method removed, `BookingsWebConfig`'s literal-path list updated.
- `users.phone`: registration DTO (`customer.phone`, required for `CUSTOMER`), `GET
  /api/users/me` response (`phone` field), `OrderDetailResponse.customerPhone` (populated by
  the existing, unmodified `getOrderDetail` authorization check, §9.1 — no new authorization
  code path).
- New/finalized `ErrorCode`: `BOOKING_TIME_UNAVAILABLE`. `SLOT_UNAVAILABLE` becomes
  vestigial (kept, not deleted, §9.2.2).

**Depends on**: M1 (`AvailabilityDerivationService`/`deriveCalendar` must exist for
`deriveAvailableWindows` and `createOrder`'s pre-check to build on). The `users.phone`
sub-scope has no dependency on M1 and could technically ship standalone, but is kept in this
milestone per the grouping specified for this feature.

**Acceptance criteria**:
- Registration: a new `CUSTOMER` registration without `customer.phone` → `400
  VALIDATION_ERROR`; with it → `phone` persisted, returned on `GET /api/users/me`. A
  `PROFESSIONAL` registration is unaffected (no `phone` field in that branch, column stays
  `NULL`).
- `POST /api/bookings/orders` with a `bookedStart` inside a derived-available window → `201`,
  `bookedEnd = bookedStart + 60 min`, `slot_id = NULL` on the persisted row.
- `POST /api/bookings/orders` with a `bookedStart` overlapping a block, outside working
  hours, or overlapping an existing booking → `409 BOOKING_TIME_UNAVAILABLE`.
- Concurrent-request simulation: two simultaneous `createOrder` calls for the same
  professional with overlapping `bookedStart`/`bookedEnd` → exactly one succeeds, the other
  gets `409 BOOKING_TIME_UNAVAILABLE` via the `ck_orders_no_overlap` catch path (not a raw
  `500`).
- `GET .../available-windows?issueId=` returns only windows `>= 60` minutes long; a
  20-minute gap between a block and a booking never appears in the response.
- `GET /api/bookings/orders/{orderId}` (`OrderDetailResponse`) returns `customerPhone` for
  the assigned professional starting at `PENDING` (not gated to `CONFIRMED`) — verified with
  a still-`PENDING` order.
- Full regression pass on `createSosOrder`, `accept`/`reject`/`cancel`/`on-the-way`/
  `complete` — zero behavior change.
- `docs/architecture/data-model.md`, `api-contract-bookings.md`, `api-contract.md`, and the
  relevant package `README.md`s updated to reflect the new `phone` column/field and the
  reworked order-creation contract (per the shared "every doc reflects new schema/contract
  changes" rule).

### M3 — Frontend: working-hours setup/edit UI

**Scope**: `WorkingHoursForm.tsx` (§7.2), wired to `GET`/`PUT /api/availability/working-hours`.
Rendered standalone (not yet inside the full calendar page) — can ship and be QA'd before
M4's grid exists, reducing per-milestone risk.

**Depends on**: M1 only.

**Acceptance criteria**: first-time setup flow (empty week → form → save → re-fetch shows
saved week); later-edit flow; disabled-day toggle correctly clears/hides time inputs;
validation errors surfaced per field; loading/error states present.

### M4 — Frontend: read-only weekly calendar grid

**Scope**: `WeeklyCalendarGrid.tsx` (§7.3, view-only — no click interactions yet), consuming
`GET /api/availability/calendar`. Replaces `AvailabilityPage`'s slot section with
`WeeklyAvailabilityPage` composing `SosAvailabilityToggle` + `WorkingHoursForm` entry point
+ the new read-only grid. `SlotForm`/`SlotList` removed from this page's render tree (files
kept, per §7.1) — this is the point at which new Standard-slot creation becomes unreachable
from the UI; no further sign-off is needed for this, per §9.2's resolution.

**Depends on**: M1. Composes alongside M3's `WorkingHoursForm` entry point on the same page,
though the grid itself only technically needs M1's calendar-read endpoint.

**Acceptance criteria**: correct rendering of the three visual states plus outside-working-
hours background, against real seeded data reproducing the §36 example; week navigation
(prev/next/"today"); mobile single-day/day-switcher layout (§7.4) vs. desktop 7-column
layout; loading/error states (§30); no click affordance yet (interactions are M5).

### M5 — Frontend: block create/edit/delete, booked-block navigation, phone display

**Scope**: new `Modal`/`BottomSheet` shared primitive (§7.4); `CalendarBlockModal.tsx`
(create + edit + delete, wired to §4.3/§4.4/§4.5); click-routing logic in
`WeeklyCalendarGrid` (`AVAILABLE`→create, `BLOCKED`→edit/delete, `BOOKED`→navigate);
`OrderTrackingPage.tsx` extensions (§7.5) — the counterparty-name-bug fix, the
week-context-preserving back-navigation (§43), and the **customer-phone display** for a
professional viewer (§9.1/§7.5 point 4); coarse polling refresh (§31).

**Depends on**: M1 (block endpoints), M4 (grid to click on), **and M2** — the phone field
must exist server-side (`OrderDetailResponse.customerPhone`) before this milestone's
`OrderTrackingPage` extension can render it. This is the one cross-cutting dependency in
this plan: M5's block-CRUD sub-scope only needs M1/M4, but its phone-display sub-scope needs
M2 too — flagged explicitly so `pronto-coding` isn't dispatched on this milestone before M2
lands.

**Acceptance criteria**: full §35-36 interaction matrix manually verified (including that a
`BOOKED` click **never** opens any block-editing UI, per §15); §43's back-navigation
round-trip (open calendar on week N+2 → click a booked block → back button returns to week
N+2, not the current week); §16's booking-summary content checklist (order id, status,
category, description, urgency tag, booked start/end, ETA when present, customer/professional
name per role, full address snapshot, **customer phone for a professional viewer, from
`PENDING` onward**, issue images via presigned URLs, existing actions); concurrent-update
behavior (accept a booking in one tab, confirm the calendar reflects it within one polling
interval in another).

### M6 — Frontend: booking-flow rework (`SlotPicker.tsx` → direct start-time selection)

**Scope**: §9.2.3/§7.6 in full — `deriveStartTimeCandidates` utility, `SlotPicker.tsx`
rework (recommend renaming to `StartTimePicker.tsx`), `BookingFlowPage` wiring to the new
`available-windows` endpoint and the new `CreateOrderRequest` shape.

**Depends on**: **M2 only** (needs the new `available-windows` endpoint and the new
`CreateOrderRequest` shape) — does **not** depend on M3/M4/M5, which are professional-facing
calendar screens; this is the customer-facing booking flow, an entirely separate surface.
`pronto-lead` may dispatch M6 in parallel with M3-M5 once M2 is done, if team capacity
allows — noted as an option, not a requirement (the default assumption is the linear
M1→M2→M3→M4→M5→M6 order above).

**Acceptance criteria**: professional with configured working hours + one block + one
existing booking → customer's booking flow shows correct start-time chips (excluding
blocked/booked/outside-working-hours ranges, respecting the 60-minute minimum window size);
selecting a chip and submitting creates an order with the correct `bookedStart`/derived
`bookedEnd`; a start time that becomes unavailable between fetch and submit (raced by another
customer) surfaces `BOOKING_TIME_UNAVAILABLE` as a clear, retryable error, not a crash; no
other booking-flow step regresses (professional-list step, address entry, confirmation step,
SOS path — entirely unaffected, unchanged).
