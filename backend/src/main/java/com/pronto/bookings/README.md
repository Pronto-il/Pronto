# `bookings`

## Purpose

`Orders` — Standard + SOS booking flows, accept/reject, and status transitions.

Implements `docs/architecture/api-contract-bookings.md` §2.2-2.9 (Standard path,
Milestone 3) and §2.12-2.13 (SOS path, Milestone 4).

## Responsibilities

- `GET /api/bookings/professionals?issueId=` — professional listing for a Standard booking,
  filtered by the issue's category, joined to `users` to exclude soft-deleted professionals,
  ordered `base_price ASC` (judgment call, §7 of the contract doc). Requires the issue to be
  owned by the caller, have `urgencyType = STANDARD` (`409 ISSUE_URGENCY_MISMATCH`
  otherwise — added Milestone 4, fixing a pre-existing Milestone 3 gap where this endpoint
  never validated urgency type at all, contract doc §3.10/§6 item 5), and `status = OPEN`
  (`409 ISSUE_NOT_BOOKABLE` otherwise).
- `GET /api/bookings/professionals/{professionalId}/slots?issueId=` — one professional's
  open, future `availability_slots`, ordered by `start_time ASC`. Same
  issue-ownership/urgency-type/bookable checks as the listing endpoint (including the
  Milestone 4 `ISSUE_URGENCY_MISMATCH` fix), plus `400 CATEGORY_MISMATCH` if the
  professional's category doesn't match the issue's.
- `POST /api/bookings/orders` — creates the order. Same urgency-type check as the two
  endpoints above (Milestone 4 fix), then atomically claims the chosen slot
  (`UPDATE availability_slots ... WHERE is_available = true AND start_time > now()`,
  `409 SLOT_UNAVAILABLE` on 0 affected rows) and transitions the issue
  `OPEN -> BOOKED` (`UPDATE issues ... WHERE status = 'OPEN'`, rolling back the slot claim
  too on 0 affected rows), all in one `@Transactional` method, before inserting the `orders`
  row (`order_status = PENDING`, `final_price` initialized from `professional.basePrice`,
  `slot_id` always set for a Standard order).
- `GET /api/bookings/sos-professionals?issueId=` — **new, Milestone 4.** SOS-path sibling
  of the Standard listing above: filtered by the issue's category **and** currently
  `sos_availability.is_available = true` (join added to `ProfessionalListingRepository`),
  same soft-delete exclusion and `base_price ASC` ordering. Requires `urgencyType = SOS`
  (`409 ISSUE_URGENCY_MISMATCH` otherwise — built in from the start, not a retrofit, since
  this endpoint is new) and `status = OPEN`. An empty list is a valid `200`, not an error —
  it's the backend shape behind PRD §3.5.6's "no-available-professional message," a
  frontend rendering concern.
- `POST /api/bookings/sos-orders` — **new, Milestone 4.** Creates an SOS order: no slot
  selection at all (`CreateSosOrderRequest` has one fewer field than `CreateOrderRequest`).
  Same issue-ownership/`urgencyType = SOS`/bookable checks, then a **plain read-check** (not
  an atomic claim, unlike the Standard slot claim) of the professional's `sos_availability`
  row — `409 SOS_PROFESSIONAL_UNAVAILABLE` if missing or `isAvailable != true`. This is the
  backend implementation of PRD §3.5.6's "becomes unavailable" branch (contract doc §2.13/
  §3.11 has the full design reasoning for why a plain read is correct here, unlike the
  exclusive slot claim). On success: `issues.status -> BOOKED` (same `bookIfOpen` mechanism
  the Standard path uses), then inserts the `orders` row with `bookedStart = now()`,
  `bookedEnd = NULL`, `slotId = NULL`.
- `POST /api/bookings/orders/{orderId}/accept` — `PENDING -> CONFIRMED`, professional-owner
  only. `issues.status` is not touched (stays `BOOKED`). Unmodified since Milestone 3 —
  works identically for SOS and Standard orders (no `urgency_type` branching anywhere in
  this method or in `OrderRepository.acceptIfPending`).
- `POST /api/bookings/orders/{orderId}/reject` — `PENDING -> REJECTED`, professional-owner
  only. Releases the slot (a safe no-op for an SOS order, whose `slotId` is always `null`)
  and reverts the issue to `OPEN` — the reject-return-to-list flow's server-side half, for
  both booking paths. Also unmodified since Milestone 3 — this is PRD §3.5.6's "rejects the
  request" trigger for SOS, reused verbatim.
- `POST /api/bookings/orders/{orderId}/cancel` — either party, state-dependent (`CUSTOMER`:
  `PENDING`/`CONFIRMED`/`ON_THE_WAY`; `PROFESSIONAL`: `CONFIRMED`/`ON_THE_WAY` only — a
  professional backing out of a still-`PENDING` order must use `reject` instead). No
  route-level role gate (either role may call this) — actor/authorization resolved entirely
  in `BookingsService` once the order is loaded. Unmodified since Milestone 3, urgency-
  agnostic.
- `GET /api/bookings/orders/{orderId}` — tracking/status endpoint, enriched with
  `customerName`/`professionalName`. Party-to-the-order authorization only (either role, no
  route-level gate). Unmodified since Milestone 3, urgency-agnostic.
- `GET /api/bookings/orders/me` — self-scoped list (customer's own orders, or a
  professional's incoming/past orders), optional `status` filter, no pagination. Unmodified
  since Milestone 3, urgency-agnostic — this is how a professional discovers a `PENDING` SOS
  order against them, same as for Standard.

**`accept`/`reject`/`cancel`/`GET .../{orderId}`/`GET .../me` received zero code changes
in Milestone 4** — confirmed by QA to already generalize correctly to SOS orders, per the
contract doc §3.7's original Milestone-3 prediction (now verified against the real code,
not just re-asserted).

## Key classes

| Class | Role |
|---|---|
| `entity.Order` | JPA entity for `orders`. Exposes no setters for `orderStatus`/`cancelledBy` — every transition goes through `repository.OrderRepository`'s atomic `UPDATE` methods, never a load-mutate-save round trip. Unchanged in Milestone 4 — its constructor already accepted nullable `slotId`/`bookedEnd`, used by `createSosOrder` with no entity change needed. |
| `entity.OrderStatus` / `entity.CancelledBy` | Enums mirroring `orders.order_status` (7 values, post-`V11`) / `orders.cancelled_by`. |
| `repository.OrderRepository` | `JpaRepository`, plus `acceptIfPending`/`rejectIfPending`/`cancelIfStatus` (the atomic guarded transitions, §3.2) and the self-listing/`latestOrder`/professional-authorization finder methods. Unchanged in Milestone 4 — no `urgency_type`/`slot_id` branching in any `@Query`. |
| `repository.ProfessionalListingRepository` | A narrow, read-only query interface over `professionals`/`users` (§2.2) projected into `dto.ProfessionalCard` — deliberately lives here, not in `professionals`, to avoid a reverse `professionals -> bookings` dependency (see its Javadoc). As of Milestone 4, exposes two queries: `listByCategory` (§2.2, Standard) and `listSosAvailableByCategory` (§2.12, SOS — additionally joined to `com.pronto.availability.entity.SosAvailability` filtering on `isAvailable = true`). |
| `dto.*` | Wire shapes for all ten endpoints (§2.2-2.9, §2.12-2.13) — `OrderResponse` is shared by create/accept/reject **and** the new `createSosOrder` (identical shape, differing only in values); `OrderDetailResponse`/`OrderSummaryResponse` are the richer/leaner shapes for get-by-id vs. list-mine, mirroring the pattern M1 used for `/api/users/me`. `dto.CreateSosOrderRequest` (new, Milestone 4) is `CreateOrderRequest` minus `slotId` — SOS has no slot selection. |
| `service.BookingsService` | All business logic for §2.2-2.9 and §2.12-2.13, including the atomic-transaction sequencing in `createOrder`/`createSosOrder` and the actor/authorization resolution for `cancel`/`getOrderDetail`/`listMine`. Milestone 4 added `listSosProfessionals`/`createSosOrder` plus a shared `urgencyMismatch(...)` helper/exception factory called from `listProfessionals`/`listSlots`/`createOrder` (Standard) and `listSosProfessionals`/`createSosOrder` (SOS) alike. |
| `controller.BookingsController` | `/api/bookings/professionals`, `/api/bookings/professionals/{id}/slots`, `/api/bookings/orders` (+ `/accept`/`/reject`/`/cancel`/`/{orderId}`/`/me`), and, as of Milestone 4, `/api/bookings/sos-professionals` + `/api/bookings/sos-orders`. Path/query ids are parsed manually so a malformed value produces this app's standard error envelope (`404` for a path id, `400 VALIDATION_ERROR` for a query id) rather than Spring's default type-mismatch handling. |
| `config.BookingsWebConfig` | Two separate, precisely-scoped `RoleRequiredInterceptor` registrations (`CUSTOMER` on the customer-only routes, `PROFESSIONAL` on `accept`/`reject`) — no blanket pattern, since this package mixes roles per-route. As of Milestone 4, the `CUSTOMER` registration's literal path list also includes `/api/bookings/sos-professionals` and `/api/bookings/sos-orders` (added explicitly — this package's literal-list design doesn't pick up new routes via a wildcard the way `availability`'s config does). Nothing registered for `cancel`/get-by-id/get-me (service-layer authorization only). |

## Interactions with other packages

- Depends on `issues` (`issues.repository.IssueRepository` — loads issues and calls
  `bookIfOpen`/`revertToOpen`, the atomic issue-status transitions that repository owns) and
  is in turn depended on by `issues` (`GET /api/issues/{id}`'s `latestOrder` field) — a
  deliberate, documented mutual dependency, not an oversight (see both packages' READMEs).
- Depends on `professionals` (`ProfessionalRepository` for professional lookup/ownership
  checks, `Professional` entity for `categoryId`/`basePrice`) and `users`
  (`UserRepository`/`User` for names and the soft-delete check).
- Depends on `availability` (`AvailabilitySlotRepository`'s `claimSlot`/`releaseSlot` — the
  atomic slot-claim/release mechanism this package's create/reject/cancel flows call into;
  `AvailabilitySlot` entity for slot start/end times at order-creation time). **As of
  Milestone 4, also depends on `SosAvailabilityRepository`** — `createSosOrder`'s plain
  read-check of a professional's `sos_availability` row (§2.13 step 9), and
  `ProfessionalListingRepository.listSosAvailableByCategory`'s join to the
  `SosAvailability` entity — a new dependency edge this package did not have in Milestone 3.
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`, including
  Milestone 3's five codes — `ISSUE_NOT_BOOKABLE`, `CATEGORY_MISMATCH`, `SLOT_UNAVAILABLE`,
  `ORDER_NOT_PENDING`, `ORDER_NOT_CANCELLABLE` — and Milestone 4's two new codes,
  `ISSUE_URGENCY_MISMATCH` and `SOS_PROFESSIONAL_UNAVAILABLE`) and
  `RoleRequiredInterceptor`/`AuthenticatedUser`.
- Will trigger `notifications` on every status transition — not built yet (Milestone 5).

## Data model

Owns the `orders` table (see `docs/architecture/data-model.md` §2.9), as amended by
Milestone 3's `V11__alter_orders_status_add_rejected.sql` (adds the genuine 7th
`order_status` value, `REJECTED` — a pre-existing gap fix, decided independently of that
milestone) and `V12__add_slot_id_to_orders.sql` (adds the nullable `slot_id` FK →
`availability_slots(id)`, the sole slot-release lookup mechanism). **Milestone 4 required no
new migration** — verified directly against the applied migration history: `sos_availability`
(`V13`, applied ahead of this milestone as a schema-gap fix — see `availability/README.md`),
and `orders.slot_id`/`booked_end`'s existing nullability (`V12`/`V8`) already tolerate every
value an SOS order writes (`slotId = NULL`, `bookedEnd = NULL`).

## Assumptions / judgment calls made during implementation

All judgment calls below follow the contract doc's explicitly stated default — no
deviation:

- **Professional-listing order** (`base_price ASC`, §2.2) and **orders-list order**
  (`created_at DESC`, §2.9) — both flagged in the contract doc as judgment calls, not
  specified by any source document; implemented exactly as the doc's stated default.
- **No dedup/exclusion of a professional who already rejected an issue** from the listing
  (§2.2/§7) — a customer can see and re-request the same professional. Not filtered, per
  the doc's explicit "leave unfiltered" call.
- **No pagination** on any list endpoint (§2.2/§2.3/§2.9) — per the doc's "acceptable at
  MVP scale" call.
- **Slot claim/release uses an application-computed `Instant.now()` passed as a bound
  query parameter**, rather than the SQL `now()` function literally embedded in the JPQL
  `UPDATE` — functionally equivalent (Postgres's `now()` is stable per-transaction, same as
  reusing one `Instant` across a single `@Transactional` method) and necessary because JPQL
  has no `now()` builtin; not a design deviation, just an implementation detail the contract
  doc (a SQL-level spec) didn't need to address.
- **Malformed path/query ids** (e.g. a non-numeric `{orderId}`) are parsed manually in
  `BookingsController` rather than via typed `@PathVariable Long`/`@RequestParam Long`, so
  they produce this app's standard error envelope with the contract's stated code (`404` for
  a path id "that doesn't resolve," `400 VALIDATION_ERROR` for a query id) instead of
  Spring's default type-mismatch handling, which doesn't use this app's envelope. Not
  explicitly specified by the contract doc (which only calls out the *valid-but-nonexistent*
  case) but a natural extension of its own §0 convention.
- **`createSosOrder`'s SOS-availability check (§2.13 step 9) is a plain read, not an atomic
  claim** — deliberate, not an oversight. Unlike an `availability_slots` row (a single
  calendar window that must not be claimed by two customers at once), `sos_availability.
  isAvailable` is a live, non-exclusive signal — nothing prevents a professional from
  legitimately receiving more than one SOS request while available. See the contract doc
  §2.13/§3.11 for the full reasoning, including the accepted narrow race (a professional
  toggling off in the few milliseconds between the read and commit) and why it's judged
  equivalent in kind to other already-accepted races in this doc (e.g. concurrent
  `accept`/`reject`).
- **`urgencyMismatch` is a single shared helper**, not five separately-worded checks — used
  identically by `listProfessionals`/`listSlots`/`createOrder` (Standard,
  `urgencyType != STANDARD`) and `listSosProfessionals`/`createSosOrder` (SOS,
  `urgencyType != SOS`), consistent with the contract doc's framing of this as one symmetric
  fix (§3.10) rather than two unrelated additions.

## Status

**Implemented and QA-validated through Milestone 4 (SOS booking flow)**, on branch `MS4`
(not yet merged to `main`, nor is `MS3` — pending the user's own git operations), per
`docs/architecture/api-contract-bookings.md` ("Milestones 3 & 4," extended in place rather
than forked into a new file) and `docs/architecture/implementation-plan.md`.

**Milestone 3 (Standard booking flow)**: QA live-validated all 8 Standard-path endpoints
against a real Postgres instance: the full happy-path flow (listing → slot pick → create →
accept → tracking), the reject → return-to-list branch (§4 of the contract doc), cancel edge
cases (actor/state permission matrix in §2.7), and ownership/role enforcement on every
endpoint — zero bugs found, no regressions to Milestone 0-2.

**Milestone 4 (SOS booking flow)**: adds `GET /api/bookings/sos-professionals` and `POST
/api/bookings/sos-orders`, plus the `ISSUE_URGENCY_MISMATCH` fix applied to the three
already-shipped Standard endpoints. QA live-validated against a real Postgres instance: the
full SOS happy path with row-level state verification at every step, both branches of PRD
§3.5.6 (professional reject via the reused `reject` endpoint; "becomes unavailable" mapped
to `409 SOS_PROFESSIONAL_UNAVAILABLE` at order-creation time, no orphaned rows), the
no-available-professional case (empty list, not an error), cross-path
`ISSUE_URGENCY_MISMATCH` validation in both directions (all 5 endpoint combinations),
ownership/role enforcement on all 4 new endpoints, and a full regression pass confirming
zero breakage to Milestones 1-3 (including confirming `accept`/`reject`/`cancel`/`GET
.../{orderId}`/`GET .../me` behave identically for SOS vs. Standard orders, since none of
those five received any code change this milestone) — **zero bugs found in this package**
this milestone (the one bug found milestone-wide, a JSON-boolean-coercion issue, was in
`availability`'s new SOS-toggle endpoint, not here — see `availability/README.md`). See
`docs/architecture/implementation-plan.md`'s Milestone 3 and Milestone 4 entries for the
full QA summaries.

`ON_THE_WAY`/`COMPLETED` progression (Milestone 6) and the `PENDING`-timeout expiry sweep
(Milestone 5) remain explicitly out of scope — SOS orders reach the same `CONFIRMED`
ceiling Standard orders do, and an SOS order stuck `PENDING` because its professional went
unavailable has no faster resolution than normal polling until the sweep exists (accepted,
documented consequence of §3.11's design, not a new gap). Professional-viewing-issue-images
(contract doc §6 item 3 / §7) remains an unresolved open item, not built here or anywhere
yet.
