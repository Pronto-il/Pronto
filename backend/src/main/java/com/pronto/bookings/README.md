# `bookings`

## Purpose

`Orders` — Standard + SOS booking flows, accept/reject, and status transitions.

Implements `docs/architecture/api-contract-bookings.md` §2.2-2.9 (Standard path,
Milestone 3), §2.12-2.13 (SOS path, Milestone 4), and §2.16-2.17 (job-status
progression, `ON_THE_WAY`/`COMPLETED`, Milestone 6). **As of Milestone 8**, also implements
`docs/architecture/api-contract-professionals-reviews.md` §7 (service-location query params,
`sort=CHEAPEST|FASTEST`, the enriched `ProfessionalCard`, service-address snapshot on order
creation, and the SOS-surcharge price split) — that doc is the authoritative source for the
Milestone 8 delta; this README's Responsibilities/Key classes sections below summarize it
in place rather than restating it in full.

## Responsibilities

- `GET /api/bookings/professionals?issueId=` — professional listing for a Standard booking,
  filtered by the issue's category, joined to `users` to exclude soft-deleted professionals,
  ordered `base_price ASC` by default (judgment call, §7 of the contract doc). Requires the
  issue to be owned by the caller, have `urgencyType = STANDARD` (`409 ISSUE_URGENCY_MISMATCH`
  otherwise — added Milestone 4, fixing a pre-existing Milestone 3 gap where this endpoint
  never validated urgency type at all, contract doc §3.10/§6 item 5), and `status = OPEN`
  (`409 ISSUE_NOT_BOOKABLE` otherwise). **As of Milestone 8**: now also requires `city`/
  `street`/`houseNumber` query params (+ optional `apartment`, together the customer's
  per-request `matching.ServiceLocation`) — missing/blank required fields → `400
  VALIDATION_ERROR`, one `FieldError` per missing field. Each returned card is enriched
  (post-fetch, in Java, never in SQL) with `profileImageUrl`/`averageRating`/`reviewCount`/
  `favorited` (correlated subqueries in `ProfessionalListingRepository` over `reviews`/
  `favorites`, resolved/converted in `BookingsService`) and `sameCity`/`distanceKm`/
  `baseTravelTimeMinutes`/`trafficAdjustmentMinutes`/`etaMinutes` (via
  `matching.DistanceEtaStrategy`, one uniform `requestTime = Instant.now()` per listing
  call). An optional `sort` param (`CHEAPEST` default/`FASTEST`) controls final ordering —
  `CHEAPEST` leaves the DB's `base_price ASC` order untouched; `FASTEST` re-sorts the
  already-enriched list in-memory by `etaMinutes` ascending (necessarily in-memory —
  `etaMinutes` is never a database column). See
  `docs/architecture/api-contract-professionals-reviews.md` §7.1-§7.3 for the full spec.
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
  row (`order_status = PENDING`, `slot_id` always set for a Standard order). **As of
  Milestone 8**: the request body also requires `serviceCity`/`serviceStreet`/
  `serviceHouseNumber` (+ optional `serviceApartment`) — persisted verbatim onto the new
  `orders.service_*` columns as a point-in-time snapshot, **not** cross-validated against
  whatever `city`/`street`/`houseNumber` the customer used on the preceding listing call
  (a flagged, accepted gap — see `docs/architecture/api-contract-professionals-reviews.md`
  §9 item 4). `final_price` is now computed as `basePriceSnapshot + sosSurcharge`
  (`basePriceSnapshot = professional.basePrice` at booking time, `sosSurcharge = 0.00`
  always for a Standard order, explicitly set in the insert rather than relying on the DB
  column's `DEFAULT 0` alone) and both components are persisted alongside `final_price` for
  display (`OrderResponse`/`OrderDetailResponse`'s new fields).
- `GET /api/bookings/sos-professionals?issueId=` — **new, Milestone 4.** SOS-path sibling
  of the Standard listing above: filtered by the issue's category **and** currently
  `sos_availability.is_available = true` (join added to `ProfessionalListingRepository`),
  same soft-delete exclusion and `base_price ASC` default ordering. Requires `urgencyType =
  SOS` (`409 ISSUE_URGENCY_MISMATCH` otherwise — built in from the start, not a retrofit,
  since this endpoint is new) and `status = OPEN`. An empty list is a valid `200`, not an
  error — it's the backend shape behind PRD §3.5.6's "no-available-professional message," a
  frontend rendering concern. **As of Milestone 8**: takes the identical `city`/`street`/
  `houseNumber`/`apartment`/`sort` query params and produces the identical enriched
  `ProfessionalCard` shape as the Standard listing above — same enrichment/sort code path
  (`BookingsService#enrichAndSort`), applied independently to whichever list this endpoint
  fetched.
- `POST /api/bookings/sos-orders` — **new, Milestone 4.** Creates an SOS order: no slot
  selection at all (`CreateSosOrderRequest` has one fewer field than `CreateOrderRequest`).
  Same issue-ownership/`urgencyType = SOS`/bookable checks, then a **plain read-check** (not
  an atomic claim, unlike the Standard slot claim) of the professional's `sos_availability`
  row — `409 SOS_PROFESSIONAL_UNAVAILABLE` if missing or `isAvailable != true`. This is the
  backend implementation of PRD §3.5.6's "becomes unavailable" branch (contract doc §2.13/
  §3.11 has the full design reasoning for why a plain read is correct here, unlike the
  exclusive slot claim). On success: `issues.status -> BOOKED` (same `bookIfOpen` mechanism
  the Standard path uses), then inserts the `orders` row with `bookedStart = now()`,
  `bookedEnd = NULL`, `slotId = NULL`. **As of Milestone 8**: also requires the same
  `serviceCity`/`serviceStreet`/`serviceHouseNumber` (+ optional `serviceApartment`) body
  fields as `POST /api/bookings/orders`, persisted the same way. `sosSurcharge` is always
  `SOS_SURCHARGE_AMOUNT` (a hardcoded `static final BigDecimal("50.00")` placeholder,
  explicitly flagged in its own Javadoc as not a sourced business figure — see
  `docs/architecture/api-contract-professionals-reviews.md` §7.5/§9 item 2), so
  `finalPrice = basePriceSnapshot + 50.00` for every SOS order.
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
- `POST /api/bookings/orders/{orderId}/on-the-way` — **new, Milestone 6.** `CONFIRMED ->
  ON_THE_WAY`, professional-owner only (same `ProfessionalRepository.findByUserId` ownership
  check as `accept`/`reject`/§2.16 step 3). Single guarded `UPDATE ... WHERE order_status =
  'CONFIRMED'` (`OrderRepository.onTheWayIfConfirmed`), `409 ORDER_NOT_CONFIRMED` on 0
  affected rows. `issues.status` is **not** touched — stays `BOOKED`, exactly as `accept`
  leaves it. Notifies the customer (`ORDER_ON_THE_WAY`) — the professional acted and doesn't
  need telling about their own action, same reasoning as every other transition in this
  package.
- `POST /api/bookings/orders/{orderId}/complete` — **new, Milestone 6.** `ON_THE_WAY ->
  COMPLETED`, professional-owner only, same ownership check. Single guarded `UPDATE ...
  WHERE order_status = 'ON_THE_WAY'` (`OrderRepository.completeIfOnTheWay`), `409
  ORDER_NOT_ON_THE_WAY` on 0 affected rows — **this is also the code a `CONFIRMED -> COMPLETED`
  skip-ahead attempt gets**: `ON_THE_WAY` is a mandatory intermediate step, there is no
  fallback branch that lets a professional call `complete` directly from `CONFIRMED` (contract
  doc §6 item 9 / §2.17's "Decided" note — a deliberate judgment call, not an oversight; see
  Assumptions below). On success, also transitions the issue: `IssueRepository
  .completeIfBooked(issueId, now)` — a new method mirroring `expireIfBooked`'s exact shape
  (`UPDATE issues SET status = 'COMPLETED', updated_at = now() WHERE id = :issueId AND status
  = 'BOOKED'`), called without checking its affected-row count, for the identical reason
  `expireIfPending` already doesn't check `expireIfBooked`'s result — §3.3's
  single-active-order-per-issue invariant guarantees this always affects exactly 1 row when
  reached (step 4's guarded `UPDATE` on the order only succeeds if this order was still
  `ON_THE_WAY`, which proves the issue is still `BOOKED` at that instant). Notifies the
  customer (`ORDER_COMPLETED`), same recipient reasoning as `on-the-way`. **`cancel` (§2.7)
  is unmodified and remains reachable from `ON_THE_WAY`** — its actor/state matrix already
  named `ON_THE_WAY` as valid for both actors back in Milestone 3, before either of these two
  new endpoints existed; a concurrent `complete`/`cancel` race on the same order resolves via
  the same guarded-`UPDATE` mechanism every other race in this package already relies on,
  no new coordination needed.

**`accept`/`reject`/`cancel`/`GET .../{orderId}`/`GET .../me` received zero code changes
in Milestone 4** — confirmed by QA to already generalize correctly to SOS orders, per the
contract doc §3.7's original Milestone-3 prediction (now verified against the real code,
not just re-asserted).

- **New, Milestone 5** (`docs/architecture/api-contract-notifications.md` §4.1/§4.2):
  `createOrder`/`createSosOrder` (→ `ORDER_CREATED`, to the professional), `accept` (→
  `ORDER_CONFIRMED`, to the customer), `reject` (→ `ORDER_REJECTED`, to the customer), and
  `cancel` (→ `ORDER_CANCELLED`, to the *other* party — resolved via the new
  `resolveCancelNotificationRecipient` helper) each now call
  `notificationService.recordOrderNotification(...)` as their last step, inside the same
  `@Transactional` method as the state transition itself — no separate transaction, no
  outbox. See `notifications/README.md` for the full trigger→recipient mapping and the two
  rows (`IN_APP`/`EMAIL`) each call produces.
- **New, Milestone 5**: two new public methods, `findExpiredOrderCandidateIds()` and
  `expireIfPending(Long orderId)`, back the `PENDING`-order expiry sweep. `bookings` owns the
  domain rule and the transition itself (per `data-model.md` §3 item 8's ownership split);
  `notifications.scheduler.OrderExpirySweepJob` owns the `@Scheduled` orchestration that
  calls them every 60s. Timeout constants — **decided**, no longer a pending recommendation:
  `STANDARD_PENDING_TIMEOUT = Duration.ofMinutes(15)`, `SOS_PENDING_TIMEOUT =
  Duration.ofMinutes(5)` — hardcoded `static final` fields in `BookingsService`, no
  migration. `expireIfPending` mirrors `reject`'s shape exactly (guarded `UPDATE` →
  `EXPIRED`, release the slot via the same `availabilitySlotRepository.releaseSlot(...)` call
  `reject`/`cancel` already use, transition the issue to `EXPIRED` via the new
  `issueRepository.expireIfBooked`, then `recordOrderNotification(..., ORDER_EXPIRED)` to the
  customer only) but is called by a background job, not an HTTP request: `0` affected rows
  (another caller already moved the order out of `PENDING`) is a normal, silent no-op, not an
  `ApiException` — there is no HTTP caller to report a `409` to.
- **New, Milestone 6** (`docs/architecture/api-contract-bookings.md` §2.16/§2.17): the two
  job-status progression endpoints above (`onTheWay`/`complete`), each following the exact
  `recordOrderNotification(...)` pattern Milestone 5 established — no new notification
  mechanism, just two more call sites, to `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` respectively,
  both to the customer. No `availability` package changes, no new DTO (`OrderResponse`/
  `OrderStatus` already carried `ON_THE_WAY`/`COMPLETED` as unused values since Milestone 0's
  schema), no new migration (§1.5 of the contract doc, verified against the real applied `V1`-
  `V14` list).

## Key classes

| Class | Role |
|---|---|
| `entity.Order` | JPA entity for `orders`. Exposes no setters for `orderStatus`/`cancelledBy` — every transition goes through `repository.OrderRepository`'s atomic `UPDATE` methods, never a load-mutate-save round trip. Unchanged in Milestone 4 — its constructor already accepted nullable `slotId`/`bookedEnd`, used by `createSosOrder` with no entity change needed. |
| `entity.OrderStatus` / `entity.CancelledBy` | Enums mirroring `orders.order_status` (7 values, post-`V11`) / `orders.cancelled_by`. |
| `repository.OrderRepository` | `JpaRepository`, plus `acceptIfPending`/`rejectIfPending`/`cancelIfStatus` (the atomic guarded transitions, §3.2) and the self-listing/`latestOrder`/professional-authorization finder methods. Unchanged in Milestone 4 — no `urgency_type`/`slot_id` branching in any `@Query`. **As of Milestone 5**, two new methods: `expireIfPending` (mirrors `rejectIfPending` exactly, target status `EXPIRED`) and `findPendingExpiryCandidateIds` (cross-entity comma-join JPQL against `Order`/`Issue`, same style as `ProfessionalListingRepository`'s existing joins — returns candidate order ids past their per-`urgencyType` cutoff for the sweep). **As of Milestone 6**, two new guarded-transition methods following the exact same shape: `onTheWayIfConfirmed` (`UPDATE ... WHERE order_status = 'CONFIRMED'`, target `ON_THE_WAY`) and `completeIfOnTheWay` (`UPDATE ... WHERE order_status = 'ON_THE_WAY'`, target `COMPLETED`) — both single-hop guards, no skip-ahead `WHERE` clause. |
| `repository.ProfessionalListingRepository` | A narrow, read-only query interface over `professionals`/`users` (§2.2) projected into `dto.ProfessionalCard` — deliberately lives here, not in `professionals`, to avoid a reverse `professionals -> bookings` dependency (see its Javadoc). As of Milestone 4, exposes two queries: `listByCategory` (§2.2, Standard) and `listSosAvailableByCategory` (§2.12, SOS — additionally joined to `com.pronto.availability.entity.SosAvailability` filtering on `isAvailable = true`). **As of Milestone 8**: both queries' `SELECT NEW ProfessionalCard(...)` projections gained `p.city`/`p.profileImageKey` and three correlated scalar subqueries — `AVG(r.rating)`/`COUNT(r)` over `com.pronto.reviews.entity.Review` (rating aggregate) and `COUNT(f)` over `com.pronto.favorites.entity.Favorite` scoped to `:customerId` (the `favorited` flag) — deliberately correlated subqueries, not a `LEFT JOIN + GROUP BY`, to avoid a wide `GROUP BY` column list across three joined tables. ETA/distance are deliberately **not** added to either query — computed in Java, post-fetch, never in SQL (see `service.BookingsService#enrichAndSort` below). |
| `dto.*` | Wire shapes for all twelve endpoints (§2.2-2.9, §2.12-2.13, §2.16-2.17) — `OrderResponse` is shared by create/accept/reject, `createSosOrder`, **and, as of Milestone 6, `onTheWay`/`complete`** (identical shape, differing only in values — `OrderStatus` already had `ON_THE_WAY`/`COMPLETED` as enum constants, no new field needed); `OrderDetailResponse`/`OrderSummaryResponse` are the richer/leaner shapes for get-by-id vs. list-mine, mirroring the pattern M1 used for `/api/users/me`. `dto.CreateSosOrderRequest` (new, Milestone 4) is `CreateOrderRequest` minus `slotId` — SOS has no slot selection. **No new DTO added in Milestone 6. As of Milestone 8**: `ProfessionalCard` gained `profileImageUrl`/`averageRating`/`reviewCount`/`favorited`/`sameCity`/`distanceKm`/`baseTravelTimeMinutes`/`trafficAdjustmentMinutes`/`etaMinutes` — a two-stage-construction record (see its own Javadoc): the JPQL-projection constructor `ProfessionalListingRepository` calls carries raw column values and rating/favorite subquery results with placeholder ETA fields; `BookingsService#enrichAndSort` produces the final card via the canonical (all-fields) constructor. `CreateOrderRequest`/`CreateSosOrderRequest` gained required `serviceCity`/`serviceStreet`/`serviceHouseNumber` (+ optional `serviceApartment`). `OrderResponse`/`OrderDetailResponse` gained `basePriceSnapshot`/`sosSurcharge` and the four `service*` fields. New enum `dto.ProfessionalSort` (`CHEAPEST`/`FASTEST`). `OrderSummaryResponse` (list-mine) was **not** changed — the new fields are detail/create-response-only. |
| `service.BookingsService` | All business logic for §2.2-2.9, §2.12-2.13, and §2.16-2.17, including the atomic-transaction sequencing in `createOrder`/`createSosOrder` and the actor/authorization resolution for `cancel`/`getOrderDetail`/`listMine`. Milestone 4 added `listSosProfessionals`/`createSosOrder` plus a shared `urgencyMismatch(...)` helper/exception factory called from `listProfessionals`/`listSlots`/`createOrder` (Standard) and `listSosProfessionals`/`createSosOrder` (SOS) alike. **As of Milestone 5**: constructor gains a new required `notifications.service.NotificationService` dependency; `createOrder`/`createSosOrder`/`accept`/`reject`/`cancel` each gained a trailing `recordOrderNotification(...)` call; two new public methods, `findExpiredOrderCandidateIds()`/`expireIfPending(Long)`, plus the two hardcoded timeout constants (`STANDARD_PENDING_TIMEOUT`/`SOS_PENDING_TIMEOUT`) — see Responsibilities above for the full writeup. **As of Milestone 6**: two new public methods, `onTheWay(Long callerId, Long orderId)` and `complete(Long callerId, Long orderId)`, each resolving the caller's `professionals.id` (same `resolveProfessionalId` helper `accept`/`reject` already use), calling the matching `OrderRepository` guarded transition, and finishing with a `recordOrderNotification(...)` call to the customer — see Responsibilities above for the full writeup, including `complete`'s additional `issueRepository.completeIfBooked(...)` call. **As of Milestone 8**: constructor gains two new required dependencies, `matching.DistanceEtaStrategy` and `storage.client.StorageClient`; `listProfessionals`/`listSosProfessionals` now take a `matching.ServiceLocation`/sort-param pair and call the new private `enrichAndSort(...)` helper (resolves each card's profile-image URL, computes distance/ETA via `DistanceEtaStrategy#calculate` with one uniform `Instant.now()` per listing call, and re-sorts by `etaMinutes` when `sort == FASTEST`); `createOrder`/`createSosOrder` gained the service-address snapshot (persisted verbatim onto the new `Order` constructor params) and the `basePriceSnapshot`/`sosSurcharge` computation (`sosSurcharge` always `0.00` for Standard, always the new `SOS_SURCHARGE_AMOUNT` constant for SOS); new private helpers `parseSort(String)` (mirrors `parseStatus`'s "blank means default/no-filter" convention) and the `SOS_SURCHARGE_AMOUNT` constant. |
| `controller.BookingsController` | `/api/bookings/professionals`, `/api/bookings/professionals/{id}/slots`, `/api/bookings/orders` (+ `/accept`/`/reject`/`/cancel`/`/{orderId}`/`/me`), as of Milestone 4 `/api/bookings/sos-professionals` + `/api/bookings/sos-orders`, and, **as of Milestone 6, `/api/bookings/orders/{orderId}/on-the-way` + `/api/bookings/orders/{orderId}/complete`** (`POST`, same manual path-id-parsing convention as `accept`/`reject`). Path/query ids are parsed manually so a malformed value produces this app's standard error envelope (`404` for a path id, `400 VALIDATION_ERROR` for a query id) rather than Spring's default type-mismatch handling. **As of Milestone 8**: `listProfessionals`/`listSosProfessionals` gained `city`/`street`/`houseNumber`/`apartment`/`sort` query params and a new private `parseServiceLocation(...)` helper (collects all missing required fields into one `400 VALIDATION_ERROR` response, same "collect every failure" spirit as `@Valid` body validation). |
| `config.BookingsWebConfig` | Two separate, precisely-scoped `RoleRequiredInterceptor` registrations (`CUSTOMER` on the customer-only routes, `PROFESSIONAL` on `accept`/`reject`) — no blanket pattern, since this package mixes roles per-route. As of Milestone 4, the `CUSTOMER` registration's literal path list also includes `/api/bookings/sos-professionals` and `/api/bookings/sos-orders` (added explicitly — this package's literal-list design doesn't pick up new routes via a wildcard the way `availability`'s config does). **As of Milestone 6**, the `PROFESSIONAL` registration's literal path list also includes `/api/bookings/orders/*/on-the-way` and `/api/bookings/orders/*/complete` — same "literal-list doesn't pick up new routes automatically" reasoning. Nothing registered for `cancel`/get-by-id/get-me (service-layer authorization only). |

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
  `ORDER_NOT_PENDING`, `ORDER_NOT_CANCELLABLE` — Milestone 4's two new codes,
  `ISSUE_URGENCY_MISMATCH` and `SOS_PROFESSIONAL_UNAVAILABLE`, and **Milestone 6's two new
  codes, `ORDER_NOT_CONFIRMED` and `ORDER_NOT_ON_THE_WAY`** — both `409`, following the same
  single-expected-source-status naming precedent `ORDER_NOT_PENDING` already set) and
  `RoleRequiredInterceptor`/`AuthenticatedUser`.
- **New, Milestone 5**: depends on `notifications.service.NotificationService`/
  `notifications.entity.NotificationMessageType` (constructor-injected into
  `BookingsService`), called from `createOrder`/`createSosOrder`/`accept`/`reject`/`cancel`/
  `expireIfPending` on every successful transition (`api-contract-notifications.md`
  §4.1/§4.2). This is one half of a deliberate, flagged `bookings ↔ notifications`
  package-level dependency cycle — the other half is `notifications.scheduler
  .OrderExpirySweepJob` (in `notifications`) depending on `BookingsService` (here) to run the
  expiry sweep. Not a Java-level compile cycle (no single class pair mutually imports each
  other) and not an oversight — the direct, unavoidable consequence of the sweep-ownership
  split `data-model.md` §3 item 8 already decided. See `notifications/README.md` for the full
  reasoning. **Milestone 6 reuses this same dependency edge** (`onTheWay`/`complete` each call
  `recordOrderNotification(...)`) — no new dependency edge to `notifications` was introduced,
  the constructor injection and the cycle shape are unchanged from Milestone 5.
- **Milestone 6 introduces no new dependency edge to `availability`** — `onTheWay`/`complete`
  don't touch slots or `sos_availability` at all (job-status progression happens entirely
  after a slot has already been claimed/released by earlier transitions). `issues` remains the
  same dependency as before — `complete`'s new `issueRepository.completeIfBooked(...)` call
  uses the existing `IssueRepository` this package already depended on, just one more method
  on it (mirroring `expireIfBooked`, itself added in Milestone 5).
- **New, Milestone 8**: depends on `matching` (`DistanceEtaStrategy`/`EtaResult`/
  `ServiceLocation`, constructor-injected into `BookingsService`) for distance/ETA
  computation, and on `storage` (`StorageClient#resolveUrl`) to resolve each listed
  professional's profile-image URL — both new dependency edges. Also gains an **indirect**
  read dependency on `reviews`/`favorites`, expressed entirely at the SQL level inside
  `ProfessionalListingRepository`'s two `@Query` methods (correlated subqueries referencing
  `com.pronto.reviews.entity.Review`/`com.pronto.favorites.entity.Favorite` directly by
  fully-qualified JPQL entity name) rather than through either package's service or
  repository layer — the same narrow-cross-package-repository-read pattern this interface
  already used for `professionals`/`sos_availability`, extended to two more packages.
  `professionals.entity.Professional`'s new `city`/`profileImageKey` columns are read via
  the existing `professionals` dependency edge, no new edge needed there.

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

**Milestone 5 also required no new migration to `orders`** — the `PENDING`-timeout values
(15 min `STANDARD` / 5 min `SOS`) are hardcoded application-level constants in
`BookingsService`, not schema values, per `api-contract-notifications.md` §4.5's explicit
"no migration" call. The one migration this milestone did add,
`V14__alter_notifications_message_type_add_rejected.sql`, touches only `notifications
.message_type`'s `CHECK` — see `notifications/README.md`, not this package.

**Milestone 6 required no new migration either** — verified directly against
`backend/src/main/resources/db/migration/`, which contains exactly `V1`-`V14`
(`api-contract-bookings.md` §1.5). `orders.order_status`'s `CHECK` constraint has allowed
`ON_THE_WAY` and `COMPLETED` since the *original* `V8__create_orders.sql`, and
`issues.status`'s `CHECK` has allowed `COMPLETED` since the original `V6__create_issues.sql`
— both values were always present in the schema, just unreachable via any endpoint until this
milestone built the first ones that actually produce them. Milestone 6 is the first milestone
to *reach* these already-tolerated values, not the first to need the schema to allow them —
the same is true of `notifications.message_type`'s `ORDER_ON_THE_WAY`/`ORDER_COMPLETED`
values, present since the original `V9__create_notifications.sql` and now finally given a
producing call site (see "Responsibilities" above).

**Milestone 8 added two new migrations directly to `orders`**:
`V18__alter_orders_add_service_address.sql` (`service_city`/`service_street`/
`service_house_number`/`service_apartment`, all nullable at the DB level — required at the
API/Bean-Validation layer for new writes instead, since existing orders have no backfillable
service address) and `V19__alter_orders_add_sos_pricing.sql` (`base_price_snapshot`,
nullable, backfilled from `final_price` for existing rows; `sos_surcharge`, `NOT NULL
DEFAULT 0`, `CHECK (sos_surcharge >= 0)` — every order, past and future, has a well-defined
surcharge amount). Both migrations are owned by this package (the `orders` table), not
`reviews`/`favorites`/`matching` — see `docs/architecture/data-model.md` §2.9 (amended) and
`docs/architecture/api-contract-professionals-reviews.md` §1.4-§1.5 for the full column
specs.

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
- **`issues.status` transitions to `EXPIRED` unconditionally when `expireIfPending`'s guarded
  `UPDATE` succeeds** — not a runtime branch on "did the customer already rebook," per
  `api-contract-notifications.md` §4.5's reasoning: the single-active-order-per-issue
  invariant (§3.3) guarantees an issue can only be `BOOKED` while *this* order is still
  `PENDING`, so at the moment the guarded transition actually succeeds, "no replacement order
  exists yet" is always already true. If a concurrent `reject`/`cancel`/`accept` beat the
  sweep to this order, `expireIfPending`'s own guard returns `0` and does nothing further —
  the same "lost the race" pattern `reject`/`cancel` already use.
- **The expiry sweep's `notifications → bookings` call direction is a deliberate, flagged
  package-level dependency cycle**, not an unnoticed side effect — see the Interactions
  section above.
- **`ON_THE_WAY` is a mandatory intermediate step between `CONFIRMED` and `COMPLETED` — a
  professional cannot call `complete` directly from `CONFIRMED`, deliberately, not an
  oversight.** `completeIfOnTheWay` guards on `order_status = 'ON_THE_WAY'` only, with no
  fallback branch that also accepts `CONFIRMED`; a `CONFIRMED` order hitting `complete` fails
  the guard and gets the same `409 ORDER_NOT_ON_THE_WAY` any other non-`ON_THE_WAY` order
  would (contract doc §6 item 9 / §2.17). Reasoning, condensed from the contract doc: PRD
  §3.6.1 names `Pending, Confirmed, On the Way, Completed, Cancelled, Expired` as an explicit
  ordered sequence with no described "skip a step" path; every other guarded transition in
  this package is already a strict single-hop guard (`accept`/`reject` from `PENDING` only),
  so a multi-hop "or skip ahead" guard would be a new, unrequested pattern; and allowing the
  skip would mean the `ORDER_ON_THE_WAY` notification — this platform's concrete
  "professional is en route" signal — could silently never fire for some jobs, with no way
  for the customer (or a future bug report) to tell which behavior to expect. **Accepted
  consequence**: a professional whose job genuinely needed no travel time must still call
  `on-the-way` immediately before `complete` — two calls instead of one, judged a negligible
  UX cost against a deterministic, PRD-literal status sequence.
- **`issues.status → 'COMPLETED'` via `completeIfBooked`, guarded on `BOOKED`, called without
  checking its affected-row count** — mirrors `expireIfBooked`'s exact shape and the exact way
  `expireIfPending` already calls it (Milestone 5). Not a design deviation but the only
  internally-consistent choice given §3.3's single-active-order-per-issue invariant: by the
  time `completeIfOnTheWay`'s guarded `UPDATE` has already succeeded, this order is proven to
  still be the sole active order for its issue, which proves the issue is still `BOOKED` at
  that exact instant — an unreachable "0 rows" branch here would be dead-code risk, not a
  genuine defensive measure (contract doc §6 item 11 has the full comparison against
  `SosAvailabilityRepository`'s different "row unexpectedly missing" precedent, which *does*
  branch, because that case lacks an equivalent invariant-based proof).
- **Milestone 8 — `sort=FASTEST` is necessarily an in-memory re-sort, not a second SQL query
  variant.** `etaMinutes` is computed in Java, never persisted, so no `ORDER BY` could sort by
  it at the DB level; both `sort` values fetch the identical DB-level `base_price ASC`-ordered
  result set and enrich every card identically — `sort` only changes the *final* ordering
  step, never which cards are enriched or how.
- **Milestone 8 — `SOS_SURCHARGE_AMOUNT = 50.00` is an explicitly-flagged placeholder, not a
  sourced business figure** — a single hardcoded `static final BigDecimal` constant, same
  category of judgment call as Milestone 5's `STANDARD_PENDING_TIMEOUT`/
  `SOS_PENDING_TIMEOUT`, trivial to change later with no migration implied. Flagged directly
  in the constant's own Javadoc and restated in
  `docs/architecture/api-contract-professionals-reviews.md` §9 item 2 and
  `docs/architecture/implementation-plan.md`'s Milestone 8 entry.
- **Milestone 8 — a booking's `serviceCity`/`serviceStreet`/`serviceHouseNumber`/
  `serviceApartment` is never cross-validated against the `city`/`street`/`houseNumber`
  query params used on the preceding listing call.** The two are independent inputs on two
  independent requests; nothing links them. Judged low-risk/low-impact, not built as a
  cross-check — see `docs/architecture/api-contract-professionals-reviews.md` §9 item 4.
- **Milestone 8 — newly-registered professionals have `city = NULL`** (untouched
  `auth.service.AuthService#register`, still only sets `serviceArea`), which
  `matching.ApproximateDistanceEtaStrategy` treats as "different city" (a deliberate
  conservative default owned by `matching`, not this package) — a new professional shows
  worse ETA/`sameCity: false` by default in every listing this package produces, until they
  self-edit via `professionals`' new `PUT /api/professionals/me`. Not a bug in this package —
  documented here because it's directly observable in this package's own response shape; the
  root cause and full record live in `professionals/README.md` and
  `docs/architecture/implementation-plan.md`'s Milestone 8 entry.

## Status

**Implemented and QA-validated through Milestone 6 (Professional dashboard — job-status
progression), 2026-08-13**, on branch `MS6` (not yet merged to `main`, nor are
`MS3`/`MS4`/`MS5` — pending the user's own git operations), per
`docs/architecture/api-contract-bookings.md` ("Milestones 3, 4 & 6," extended in place rather
than forked into a new file) for the booking flows themselves, and
`docs/architecture/api-contract-notifications.md` §4.1/§4.2/§4.5 for the Milestone 5
notification-hook/expiry-sweep additions to this package, plus
`docs/architecture/implementation-plan.md`.

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

**Milestone 5 (Notifications & real-time status)**: this package gained the `NotificationService`
constructor dependency, five new `recordOrderNotification` call sites
(`createOrder`/`createSosOrder`/`accept`/`reject`/`cancel`), the two new `expireIfPending`/
`findExpiredOrderCandidateIds` methods, two new `OrderRepository` methods
(`expireIfPending`/`findPendingExpiryCandidateIds`), and the two hardcoded timeout constants
— see "Responsibilities"/"Key classes"/"Interactions" above for the full writeup. QA
live-validated against a real Postgres instance (method: live HTTP + direct `psql`
verification at every step, not just status codes): notification creation on every Standard
(`createOrder`→accept, →reject, →accept→cancel-by-customer, →accept→cancel-by-professional)
and SOS (`createSosOrder`→accept→cancel-by-customer) transition, confirmed against the
trigger→recipient mapping (§4.2 of the notifications contract doc) with the reverse recipient
confirmed to never fire; and the expiry sweep as "the strongest-verified part of the
milestone" — real `orders.created_at` backdated via direct SQL to both sides of both
boundaries (Standard 16min → `EXPIRED`, 10min → stays `PENDING`; SOS 6min → `EXPIRED`, 3min →
stays `PENDING`), full side-effect verification (`orders.order_status`/`updated_at`,
`issues.status = EXPIRED`, slot released for Standard, `slotId = NULL` safe no-op for SOS,
`ORDER_EXPIRED` notification to the customer only), and the "lost the race" case (an order
backdated past timeout but accepted via the real API before the sweep ran stayed `CONFIRMED`,
not flipped, no exception). **Zero bugs found in this package this milestone** — the one bug
found milestone-wide (a `NotificationController` `{id}`-parsing issue) was entirely inside
`notifications`, not here; see `notifications/README.md` and
`docs/architecture/implementation-plan.md`'s Milestone 5 entry for the full QA summary.

**Milestone 6 (Professional dashboard — job-status progression)**: this package gained two
new endpoints (`onTheWay`/`complete`), two new `OrderRepository` guarded-transition methods
(`onTheWayIfConfirmed`/`completeIfOnTheWay`), one new `IssueRepository` method
(`completeIfBooked`), two new `common.exception.ErrorCode` values (`ORDER_NOT_CONFIRMED`,
`ORDER_NOT_ON_THE_WAY`, both `409`), and two new `BookingsWebConfig` literal path patterns on
the existing `PROFESSIONAL`-scoped interceptor — see "Responsibilities"/"Key classes" above
for the full writeup. **No `availability` package changes, no new migration, no new DTO** —
`OrderResponse`/`OrderStatus` already supported the `ON_THE_WAY`/`COMPLETED` values. QA
live-validated against a real Postgres instance: the full happy path for both Standard and SOS
orders, driven all the way through `on-the-way` → `complete` (order creation → accept →
on-the-way → complete, row-level verification at every step: `orders.order_status`/
`updated_at`, `issues.status: BOOKED → COMPLETED` on the `complete` call only, never on
`on-the-way`); every guard-violation/skip-ahead case (`on-the-way` called on a `PENDING`,
`ON_THE_WAY`, `COMPLETED`, `CANCELLED`, and `REJECTED` order each correctly `409
ORDER_NOT_CONFIRMED`; `complete` called on a `PENDING` and, critically, a still-`CONFIRMED`
order — the deliberate `CONFIRMED → COMPLETED` skip-ahead attempt — each correctly `409
ORDER_NOT_ON_THE_WAY`, confirming `ON_THE_WAY` is enforced as a mandatory intermediate step,
not just documented as one); actor/role enforcement on both new endpoints (a customer calling
either correctly `403`s; a professional who isn't the order's own professional correctly
`403`s); confirmed `cancel` (§2.7) still works unmodified from `ON_THE_WAY` for both actors,
with no regression to its existing `PENDING`/`CONFIRMED` behavior; confirmed the
`ORDER_ON_THE_WAY`/`ORDER_COMPLETED` notifications reach the customer only — direct `psql`
verification that zero such rows were ever created for the professional, on both a Standard
and an SOS order; and a full regression pass covering Milestones 0-5, explicitly including the
highest-risk MS5 item (the `PENDING`-order expiry sweep, re-verified still correctly leaves
`ON_THE_WAY`/`COMPLETED` orders alone — the sweep only ever targets `PENDING` orders, and
Milestone 6 introduced no code path that could put an order back into `PENDING`). **Zero bugs
found, full sign-off.**

Professional-viewing-issue-images (contract doc §6 item 3 / §7) remains an unresolved open
item, not built here or anywhere yet. The `EXPIRED`-issue-cannot-be-rebooked gap first
surfaced while designing Milestone 5's expiry sweep — an `EXPIRED` issue cannot actually be
rebooked via any existing endpoint, since `createOrder`/`createSosOrder` both require
`issue.status == 'OPEN'` and nothing transitions `EXPIRED` back to `OPEN` — was confirmed
**unaffected by Milestone 6**: `on-the-way`/`complete` only ever read/write orders that are
already `CONFIRMED`/`ON_THE_WAY` (never `OPEN`/`EXPIRED` issues), so neither new endpoint
added, removed, or narrowed this gap in any way (contract doc §9 verifies this explicitly,
not just asserts it). **Resolved, Milestone 7 (2026-08-15)**: the user has ruled this is
intentional, permanent design — `EXPIRED` stays a final `issues.status` state forever, no
reopen endpoint, no relaxed booking guard on `createOrder`/`createSosOrder`; a customer who
wants service again creates a new issue. See `docs/architecture/data-model.md` §4,
`docs/architecture/api-contract-notifications.md` §7, and
`docs/architecture/hardening-plan.md` §4.1 for the full resolution record. **Slot
edit/delete was explicitly considered for Milestone 6 and explicitly declined at the
time** — a judgment call, not a silently-skipped gap (contract doc §8.2's original
reasoning). **That call was reversed in Milestone 7 (2026-08-15) by explicit user product
decision**: `PUT`/`DELETE /api/availability/slots/{slotId}` are now designed, implemented,
and QA-passed — see `availability/README.md`'s Status section for the full record (this
package, `bookings`, received no code changes for that addition; it lives entirely in
`availability`). The professional-dashboard **UI** remains entirely deferred project-wide,
consistent with every prior milestone — nothing in this milestone builds any `frontend/`
code.

**Milestone 8 (Professional Profiles, Reviews, Favorites & Matching)**: this package gained
the service-location query params/`sort` mode on both listing endpoints, the enriched
`ProfessionalCard` (profile image, rating/review-count, favorited flag, distance/ETA), the
service-address snapshot and SOS-surcharge price split on order creation, two new `orders`
migrations (`V18`/`V19`), and two new constructor dependencies (`matching.DistanceEtaStrategy`,
`storage.client.StorageClient`) — see "Responsibilities"/"Key classes"/"Interactions" above
for the full writeup. **No new `ErrorCode` values in this package** — every new failure mode
(missing service-location query params, missing service-address body fields) reuses the
existing `VALIDATION_ERROR` code via `FieldError`, and `sort`'s invalid-value case reuses the
same convention `parseStatus` already established. QA-signed-off with **zero bugs found on
functionality or security** as part of this feature set's overall sign-off — see
`docs/architecture/implementation-plan.md`'s Milestone 8 entry for the full QA summary (and
its one recorded non-blocking known gap, the `professionals.city = NULL` default for newly
registered professionals, which this package's listing enrichment directly surfaces via
`sameCity: false`/worse ETA figures). Full design/contract:
`docs/architecture/api-contract-professionals-reviews.md` §7-§9.
