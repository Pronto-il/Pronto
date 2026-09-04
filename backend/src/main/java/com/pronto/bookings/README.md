# `bookings`

## Purpose

`Orders` — Standard + SOS booking flows, accept/reject, and status transitions.

Implements `docs/architecture/api-contract-bookings.md` §2.2-2.9 (Standard path,
Milestone 3), §2.12-2.13 (SOS path, Milestone 4), and §2.16-2.17 (job-status
progression, `ON_THE_WAY`/`COMPLETED`, Milestone 6). **As of Milestone 8**, also implements
`docs/architecture/api-contract-professionals-reviews.md` §7 (service-location query params,
`sort=CHEAPEST|RECOMMENDED|FASTEST`, the enriched `ProfessionalCard`, service-address snapshot
on order creation, and the SOS-surcharge price split) — that doc is the authoritative source for the
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
  VALIDATION_ERROR`, one `FieldError` per missing field. **As of the address-flow redesign**:
  `houseNumber` must additionally be digits only (`maps.HouseNumbers`), so this route cannot be
  the one door around a rule every write path applies; and `issueId`/`categoryId` are **both
  optional at the binding layer**, with the service deciding between them. That last part is a
  bug fix: deferred authentication made a category-keyed listing the normal case, and this
  controller still parsed `issueId` as required — so every `?categoryId=...` listing was answered
  `400 VALIDATION_ERROR: issueId is required` no matter how good the address was. `GET
  .../professionals/{id}/available-windows?issueId=` had the same defect and the same fix. Each returned card is enriched
  (post-fetch, in Java, never in SQL) with `profileImageUrl`/`averageRating`/`reviewCount`/
  `favorited` (correlated subqueries in `ProfessionalListingRepository` over `reviews`/
  `favorites`, resolved/converted in `BookingsService`) and `sameCity`/`distanceKm`/
  `baseTravelTimeMinutes`/`trafficAdjustmentMinutes`/`etaMinutes` (via
  `matching.DistanceEtaStrategy`, one uniform `requestTime = Instant.now()` per listing
  call). An optional `sort` param (`CHEAPEST`/`RECOMMENDED`/`FASTEST`, default `CHEAPEST`
  for this endpoint) controls final ordering — `CHEAPEST` leaves the DB's `base_price ASC`
  order untouched; `RECOMMENDED` re-sorts the already-enriched list in-memory by
  `averageRating` descending (professionals with a `null` `averageRating` — no reviews yet —
  sort last), tiebroken by `reviewCount` descending; `FASTEST` re-sorts the already-enriched
  list in-memory by `etaMinutes` ascending (necessarily in-memory — `etaMinutes` is never a
  database column). See `docs/architecture/api-contract-professionals-reviews.md` §7.1-§7.3
  for the full spec.
- `GET /api/bookings/professionals/{professionalId}/slots?issueId=` — **retired as of the
  professional weekly availability calendar design's M2 (2026-08-18), replaced entirely (not
  kept for compatibility) by `GET .../professionals/{professionalId}/available-windows
  ?issueId=`** — see the dedicated M2 entry near the end of this section for the full
  replacement writeup; kept here, struck through in spirit, only so this bullet's own history
  (one professional's open, future `availability_slots`, ordered by `start_time ASC`) stays
  legible as a record of what Milestones 3-8 actually built.
- `POST /api/bookings/orders` — creates the order. Same urgency-type check as the two
  endpoints above (Milestone 4 fix), then, **through Milestone 8**, atomically claimed the
  chosen slot (`UPDATE availability_slots ... WHERE is_available = true AND start_time >
  now()`, `409 SLOT_UNAVAILABLE` on 0 affected rows) and transitioned the issue `OPEN ->
  BOOKED` (`UPDATE issues ... WHERE status = 'OPEN'`, rolling back the slot claim too on 0
  affected rows), all in one `@Transactional` method, before inserting the `orders` row
  (`order_status = PENDING`, `slot_id` always set for a Standard order). **As of the
  professional weekly availability calendar design's M2 (2026-08-18), this slot-claim
  mechanism is retired for Standard order creation** — see the dedicated M2 entry below for
  the full reworked shape. **As of Milestone 8**: the request body also requires
  `serviceCity`/`serviceStreet`/`serviceHouseNumber` (+ optional `serviceApartment`) —
  persisted verbatim onto the new `orders.service_*` columns as a point-in-time snapshot,
  **not** cross-validated against whatever `city`/`street`/`houseNumber` the customer used on
  the preceding listing call (a flagged, accepted gap — see
  `docs/architecture/api-contract-professionals-reviews.md` §9 item 4). `final_price` is
  computed as `basePriceSnapshot + sosSurcharge` (`basePriceSnapshot = professional.basePrice`
  at booking time, `sosSurcharge = 0.00` always for a Standard order, explicitly set in the
  insert rather than relying on the DB column's `DEFAULT 0` alone) and both components are
  persisted alongside `final_price` for display (`OrderResponse`/`OrderDetailResponse`'s new
  fields) — unchanged by M2.
- `GET /api/bookings/sos-professionals?issueId=` — **REMOVED.** Pronto SOS (`/api/sos/**`) is the product's only SOS flow; this endpoint and its `ProfessionalListingRepository.listSosAvailableByCategory` query no longer exist. The Milestone 4 description below is kept as history.
  <br>_(historical)_ SOS-path sibling
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
  fetched. **Its `sort` default matches the Standard listing's**: both endpoints default to
  `CHEAPEST` when `sort` is blank/omitted — `parseSort(String, ProfessionalSort)` takes an
  explicit per-call default rather than a single hardcoded one, but both call sites currently
  pass the same `ProfessionalSort.CHEAPEST` value. (An earlier, uncommitted draft of the
  MS3/MS4 product-corrections pass briefly had this endpoint defaulting to `FASTEST`; that was
  reconciled back to `CHEAPEST` before the corrections branch was finalized — see
  `docs/architecture/ms3-ms4-corrections-design.md` §3.)
- `POST /api/bookings/sos-orders` — **REMOVED**, together with `CreateSosOrderRequest`, `BookingsService.createSosOrder` and `ErrorCode.SOS_PROFESSIONAL_UNAVAILABLE`. The Milestone 4 description below is kept as history.
  <br>_(historical)_ Creates an SOS order: no slot
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
  package. **As of the Active Booking Floating Indicator feature (2026-08-17)**: this
  endpoint now also computes and persists `expectedArrivalAt`. Before the guarded `UPDATE`,
  `onTheWay` looks up the professional's `city` and calls
  `matching.DistanceEtaStrategy#calculate(professional.getCity(), customerLocation, now)` —
  the exact same call `enrichAndSort` already makes for listing-card ETA, `customerLocation`
  built from the order's own already-persisted `service*` snapshot (no new request-body
  field) — then sets `expectedArrivalAt = now.plus(Duration.ofMinutes(eta.etaMinutes()))` and
  passes it into the now-3-arg `onTheWayIfConfirmed(orderId, now, expectedArrivalAt)`, which
  sets `orders.expected_arrival_at` (`V23`) atomically in the same guarded `UPDATE`. This
  narrowly overrides the previously-settled "ETA is never persisted" ruling
  (`overview.md` §2, `data-model.md` §4) — see
  `docs/architecture/active-booking-floating-indicator.md` §0.1 for the full record. The
  `matching` package itself is unchanged — it still computes nothing to disk (see
  `matching/README.md`).
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
| `entity.Order` | JPA entity for `orders`. Exposes no setters for `orderStatus`/`cancelledBy` — every transition goes through `repository.OrderRepository`'s atomic `UPDATE` methods, never a load-mutate-save round trip. Unchanged in Milestone 4 — its constructor already accepted nullable `slotId`/`bookedEnd`, used by `createSosOrder` with no entity change needed. **As of the Active Booking Floating Indicator feature**: gained `expectedArrivalAt` (`@Column(name = "expected_arrival_at")`, `Instant`) — getter-only, no setter, and **not** a constructor parameter (always `null` at order-creation time, same convention as `cancelledBy` starting `null`), same "never loaded-mutated-saved, only written via the repository's atomic guarded `UPDATE`" rule this class's own Javadoc already states for every other transition field. |
| `entity.OrderStatus` / `entity.CancelledBy` | Enums mirroring `orders.order_status` (7 values, post-`V11`) / `orders.cancelled_by`. |
| `repository.OrderRepository` | `JpaRepository`, plus `acceptIfPending`/`rejectIfPending`/`cancelIfStatus` (the atomic guarded transitions, §3.2) and the self-listing/`latestOrder`/professional-authorization finder methods. Unchanged in Milestone 4 — no `urgency_type`/`slot_id` branching in any `@Query`. **As of Milestone 5**, two new methods: `expireIfPending` (mirrors `rejectIfPending` exactly, target status `EXPIRED`) and `findPendingExpiryCandidateIds` (cross-entity comma-join JPQL against `Order`/`Issue`, same style as `ProfessionalListingRepository`'s existing joins — returns candidate order ids past their per-`urgencyType` cutoff for the sweep). **As of Milestone 6**, two new guarded-transition methods following the exact same shape: `onTheWayIfConfirmed` (`UPDATE ... WHERE order_status = 'CONFIRMED'`, target `ON_THE_WAY`) and `completeIfOnTheWay` (`UPDATE ... WHERE order_status = 'ON_THE_WAY'`, target `COMPLETED`) — both single-hop guards, no skip-ahead `WHERE` clause. **As of the Active Booking Floating Indicator feature**: `onTheWayIfConfirmed`'s signature changed (breaking, single caller updated in lockstep) — now `onTheWayIfConfirmed(Long orderId, Instant now, Instant expectedArrivalAt)`, extending the same guarded `UPDATE` to also set `o.expectedArrivalAt = :expectedArrivalAt` in the identical atomic statement. `expectedArrivalAt` is computed by the caller (`BookingsService`, a pure call to `DistanceEtaStrategy`, no I/O) and passed in already-resolved — never computed inside the repository. |
| `repository.ProfessionalListingRepository` | A narrow, read-only query interface over `professionals`/`users` (§2.2) projected into `dto.ProfessionalCard` — deliberately lives here, not in `professionals`, to avoid a reverse `professionals -> bookings` dependency (see its Javadoc). As of Milestone 4, exposes two queries: `listByCategory` (§2.2, Standard) and `listSosAvailableByCategory` (§2.12, SOS — additionally joined to `com.pronto.availability.entity.SosAvailability` filtering on `isAvailable = true`). **As of Milestone 8**: both queries' `SELECT NEW ProfessionalCard(...)` projections gained `p.city`/`p.profileImageKey` and three correlated scalar subqueries — `AVG(r.rating)`/`COUNT(r)` over `com.pronto.reviews.entity.Review` (rating aggregate) and `COUNT(f)` over `com.pronto.favorites.entity.Favorite` scoped to `:customerId` (the `favorited` flag) — deliberately correlated subqueries, not a `LEFT JOIN + GROUP BY`, to avoid a wide `GROUP BY` column list across three joined tables. ETA/distance are deliberately **not** added to either query — computed in Java, post-fetch, never in SQL (see `service.BookingsService#enrichAndSort` below). |
| `dto.*` | Wire shapes for all twelve endpoints (§2.2-2.9, §2.12-2.13, §2.16-2.17) — `OrderResponse` is shared by create/accept/reject, `createSosOrder`, **and, as of Milestone 6, `onTheWay`/`complete`** (identical shape, differing only in values — `OrderStatus` already had `ON_THE_WAY`/`COMPLETED` as enum constants, no new field needed); `OrderDetailResponse`/`OrderSummaryResponse` are the richer/leaner shapes for get-by-id vs. list-mine, mirroring the pattern M1 used for `/api/users/me`. `dto.CreateSosOrderRequest` (new, Milestone 4) is `CreateOrderRequest` minus `slotId` — SOS has no slot selection. **No new DTO added in Milestone 6. As of Milestone 8**: `ProfessionalCard` gained `profileImageUrl`/`averageRating`/`reviewCount`/`favorited`/`sameCity`/`distanceKm`/`baseTravelTimeMinutes`/`trafficAdjustmentMinutes`/`etaMinutes` — a two-stage-construction record (see its own Javadoc): the JPQL-projection constructor `ProfessionalListingRepository` calls carries raw column values and rating/favorite subquery results with placeholder ETA fields; `BookingsService#enrichAndSort` produces the final card via the canonical (all-fields) constructor. `CreateOrderRequest`/`CreateSosOrderRequest` gained required `serviceCity`/`serviceStreet`/`serviceHouseNumber` (+ optional `serviceApartment`). `OrderResponse`/`OrderDetailResponse` gained `basePriceSnapshot`/`sosSurcharge` and the four `service*` fields. New enum `dto.ProfessionalSort` (`CHEAPEST`/`RECOMMENDED`/`FASTEST`). `OrderSummaryResponse` (list-mine) was **not** changed — the new fields are detail/create-response-only. **As of the MS3/MS4 product-corrections pass**: `CreateOrderRequest`/`CreateSosOrderRequest`/`OrderResponse`/`OrderDetailResponse` all gained 3 further optional fields — `serviceFloor`/`serviceEntrance`/`serviceAddressNotes` (`V22`, see Data model/Assumptions below) — bringing the service-address snapshot to the full 7-field shape. **As of the Active Booking Floating Indicator feature**: `OrderResponse`/`OrderDetailResponse`/`OrderSummaryResponse` all gained `Instant expectedArrivalAt` (positioned directly after `bookedEnd`), mirrored 1:1 from `Order.getExpectedArrivalAt()`; `OrderSummaryResponse` additionally gained `Instant updatedAt` (not previously on that lean list-mine shape at all — needed by the frontend's floating-indicator tie-break logic to express "most recently completed" among several unacknowledged `COMPLETED` orders, see `docs/architecture/active-booking-floating-indicator.md` §2.3/§5). `listMine`'s stream-mapping call site was updated for the new `OrderSummaryResponse` shape. **As of the professional weekly availability calendar M2 (2026-08-18)**: `CreateOrderRequest` lost `slotId`, gained `bookedStart` (`@NotNull Instant`); `bookedEnd` is deliberately not a field on it at all (server-derived, never client-supplied). `OrderDetailResponse` gained `customerPhone` (positioned directly after `customerName`) — **not** added to `OrderSummaryResponse`/`OrderResponse`, per the design's explicit scope limit. `dto.SlotListingResponse`/`dto.SlotSummary` were **deleted outright** (the endpoint they backed no longer exists) and replaced by two new records, `dto.AvailableWindowsResponse` (`professionalId`/`issueId`/`defaultDurationMinutes`/`timezone`/`windows`) and `dto.AvailableWindow` (`startAt`/`endAt`). |
| `service.BookingsService` | All business logic for §2.2-2.9, §2.12-2.13, and §2.16-2.17, including the atomic-transaction sequencing in `createOrder`/`createSosOrder` and the actor/authorization resolution for `cancel`/`getOrderDetail`/`listMine`. Milestone 4 added `listSosProfessionals`/`createSosOrder` plus a shared `urgencyMismatch(...)` helper/exception factory called from `listProfessionals`/`listSlots`/`createOrder` (Standard) and `listSosProfessionals`/`createSosOrder` (SOS) alike. **As of Milestone 5**: constructor gains a new required `notifications.service.NotificationService` dependency; `createOrder`/`createSosOrder`/`accept`/`reject`/`cancel` each gained a trailing `recordOrderNotification(...)` call; two new public methods, `findExpiredOrderCandidateIds()`/`expireIfPending(Long)`, plus the two hardcoded timeout constants (`STANDARD_PENDING_TIMEOUT`/`SOS_PENDING_TIMEOUT`) — see Responsibilities above for the full writeup. **As of Milestone 6**: two new public methods, `onTheWay(Long callerId, Long orderId)` and `complete(Long callerId, Long orderId)`, each resolving the caller's `professionals.id` (same `resolveProfessionalId` helper `accept`/`reject` already use), calling the matching `OrderRepository` guarded transition, and finishing with a `recordOrderNotification(...)` call to the customer — see Responsibilities above for the full writeup, including `complete`'s additional `issueRepository.completeIfBooked(...)` call. **As of Milestone 8**: constructor gains two new required dependencies, `matching.DistanceEtaStrategy` and `storage.client.StorageClient` (the latter **swapped for `storage.service.StorageService` in backend MS9** — see Interactions below); `listProfessionals`/`listSosProfessionals` now take a `matching.ServiceLocation`/sort-param pair and call the new private `enrichAndSort(...)` helper (resolves each card's profile-image URL — a presigned URL as of backend MS9, via `StorageService#getPresignedUrl(callerId, key)` — computes distance/ETA via `DistanceEtaStrategy#calculate` with one uniform `Instant.now()` per listing call, and re-sorts by `etaMinutes` when `sort == FASTEST`); `createOrder`/`createSosOrder` gained the service-address snapshot (persisted verbatim onto the new `Order` constructor params) and the `basePriceSnapshot`/`sosSurcharge` computation (`sosSurcharge` always `0.00` for Standard, always the new `SOS_SURCHARGE_AMOUNT` constant for SOS); new private helpers `parseSort(String)` (mirrors `parseStatus`'s "blank means default/no-filter" convention) and the `SOS_SURCHARGE_AMOUNT` constant. **As of the Active Booking Floating Indicator feature**: `onTheWay(Long callerId, Long orderId)` gained an ETA-computation step before its guarded transition — resolves the professional via `professionalRepository.findById(professionalId)` (a second lookup mirroring `resolveProfessionalName`'s existing "second lookup by id" pattern already present in this class), builds a `matching.ServiceLocation` from the order's own persisted `service*` snapshot, calls `distanceEtaStrategy.calculate(professional.getCity(), customerLocation, now)`, and derives `expectedArrivalAt = now.plus(Duration.ofMinutes(eta.etaMinutes()))`, passed into the now-3-arg `orderRepository.onTheWayIfConfirmed(...)`. No new constructor dependency — `distanceEtaStrategy`/`ServiceLocation`/`EtaResult`/`Duration` were all already present (`enrichAndSort` already uses the first three; `Duration` was already imported for the pending-timeout constants). **As of the professional weekly availability calendar M2 (2026-08-18)**: constructor gains a new required dependency, `availability.service.AvailabilityDerivationService` (see Interactions below). `listSlots` is renamed `listAvailableWindows`, returning the new `AvailableWindowsResponse`; `createOrder` is fully reworked per the Responsibilities section's dedicated M2 entry above (new `DEFAULT_JOB_DURATION_MINUTES`/`AVAILABLE_WINDOWS_LOOKAHEAD_DAYS`/`EXCLUSION_VIOLATION_SQLSTATE` constants, three new private helpers — `checkBookingWindowAvailable`, `mapOrderConstraintViolation`, `extractSqlState` — and a renamed error-factory helper, `bookingTimeUnavailable()`, replacing the now-unused `slotUnavailable(Long)`, which was deleted). `createSosOrder` — confirmed zero changes. |
| `controller.BookingsController` | `/api/bookings/professionals`, `/api/bookings/professionals/{id}/slots`, `/api/bookings/orders` (+ `/accept`/`/reject`/`/cancel`/`/{orderId}`/`/me`), as of Milestone 4 `/api/bookings/sos-professionals` + `/api/bookings/sos-orders`, and, **as of Milestone 6, `/api/bookings/orders/{orderId}/on-the-way` + `/api/bookings/orders/{orderId}/complete`** (`POST`, same manual path-id-parsing convention as `accept`/`reject`). Path/query ids are parsed manually so a malformed value produces this app's standard error envelope (`404` for a path id, `400 VALIDATION_ERROR` for a query id) rather than Spring's default type-mismatch handling. **As of Milestone 8**: `listProfessionals`/`listSosProfessionals` gained `city`/`street`/`houseNumber`/`apartment`/`sort` query params and a new private `parseServiceLocation(...)` helper (collects all missing required fields into one `400 VALIDATION_ERROR` response, same "collect every failure" spirit as `@Valid` body validation). **As of the professional weekly availability calendar M2 (2026-08-18)**: `/api/bookings/professionals/{id}/slots` is renamed `/api/bookings/professionals/{id}/available-windows`; the retired `listSlots` controller method is deleted outright, not kept. |
| `config.BookingsWebConfig` | Two separate, precisely-scoped `RoleRequiredInterceptor` registrations (`CUSTOMER` on the customer-only routes, `PROFESSIONAL` on `accept`/`reject`) — no blanket pattern, since this package mixes roles per-route. As of Milestone 4, the `CUSTOMER` registration's literal path list also includes `/api/bookings/sos-professionals` and `/api/bookings/sos-orders` (added explicitly — this package's literal-list design doesn't pick up new routes via a wildcard the way `availability`'s config does). **As of Milestone 6**, the `PROFESSIONAL` registration's literal path list also includes `/api/bookings/orders/*/on-the-way` and `/api/bookings/orders/*/complete` — same "literal-list doesn't pick up new routes automatically" reasoning. **As of the professional weekly availability calendar M2 (2026-08-18)**, the `CUSTOMER` registration's literal pattern `/api/bookings/professionals/*/slots` is swapped for `/api/bookings/professionals/*/available-windows` — same role gate, renamed pattern only. Nothing registered for `cancel`/get-by-id/get-me (service-layer authorization only). |

## Interactions with other packages

- Depends on `issues` (`issues.repository.IssueRepository` — loads issues and calls
  `bookIfOpen`/`revertToOpen`, the atomic issue-status transitions that repository owns) and
  is in turn depended on by `issues` (`GET /api/issues/{id}`'s `latestOrder` field) — a
  deliberate, documented mutual dependency, not an oversight (see both packages' READMEs).
- Depends on `professionals` (`ProfessionalRepository` for professional lookup/ownership
  checks, `Professional` entity for `categoryId`/`basePrice`) and `users`
  (`UserRepository`/`User` for names and the soft-delete check).
- Depends on `availability` (`AvailabilitySlotRepository`'s `claimSlot`/`releaseSlot` — the
  atomic slot-claim/release mechanism this package's `reject`/`cancel`/`expireIfPending` flows
  still call into (a safe no-op for every order created after M2, since `slot_id` is now
  always `NULL` on insert — see below); `AvailabilitySlot` entity, retained on the constructor
  though no longer read by `createOrder` itself). **As of Milestone 4, also depends on
  `SosAvailabilityRepository`** — `createSosOrder`'s plain read-check of a professional's
  `sos_availability` row (§2.13 step 9), and
  `ProfessionalListingRepository.listSosAvailableByCategory`'s join to the
  `SosAvailability` entity — a new dependency edge this package did not have in Milestone 3.
  **New, professional weekly availability calendar M2 (2026-08-18)**: `BookingsService` gains
  a constructor dependency on `availability.service.AvailabilityDerivationService` — the same
  M1-built derivation engine the calendar read endpoint uses, reused here (not
  re-implemented) for two purposes: (1) `createOrder`'s pre-check
  (`deriveCalendar(professionalId, bookedStart, bookedEnd)`, confirming the requested range is
  fully contained in a single `AVAILABLE` segment before attempting the insert); (2) the new
  `listAvailableWindows` endpoint's `deriveAvailableWindows(professionalId, from, to,
  minDuration)` call. Justified in the design (§9.2.2) over `bookings` re-implementing its own
  "is this professional free right now" query: that would duplicate the exact subtract-blocks/
  subtract-bookings algorithm §5 of the design already built and risk drifting from the
  calendar's own notion of availability over time — the same duplication-avoidance reasoning
  that already justified the calendar endpoint's own existence (design §4.6). One shared
  derivation engine, now three callers (the calendar read endpoint, order-creation validation,
  and the available-windows listing), not three independent implementations of the same
  business rule. This is a one-directional dependency (`bookings.service ->
  availability.service`) — `AvailabilityDerivationService` has no dependency back on
  `BookingsService`, so no circular bean graph is introduced (it does, however, already depend
  on `bookings.repository.OrderRepository` directly, a pre-existing M1 dependency edge in the
  opposite direction at the repository layer only — see `availability/README.md` for that
  edge's own justification, unaffected by this addition).
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
  computation, and on `storage` to resolve each listed professional's profile-image URL —
  both new dependency edges. **As of backend MS9 (2026-08-18)**: this package's `storage`
  dependency changed from directly injecting `storage.client.StorageClient`
  (`resolveUrl(key)`, a permanent, non-expiring proxy URL — now removed from that interface
  entirely) to injecting `storage.service.StorageService`
  (`getPresignedUrl(callerId, key)`, a time-limited presigned URL, 300s default TTL) —
  `enrichAndSort` now threads `callerId` through to this call, since `getPresignedUrl`
  reuses the same ownership/visibility check every other presign call site goes through (a
  no-op in practice for these public `professionals/`-prefixed keys, but keeps this call
  site on the one consistent mechanism the rest of the app uses, rather than a one-off).
  See `storage/README.md` and
  `docs/architecture/backend-ms9-presigned-image-urls-design.md` §9.1. Also gains an **indirect**
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

**MS3/MS4 product-corrections pass added one further migration to `orders`**:
`V22__alter_orders_add_service_address_details.sql` (`service_floor VARCHAR(20)`/
`service_entrance VARCHAR(20)`/`service_address_notes VARCHAR(500)`, nullable at the DB level,
optional at the API layer — same convention as `V18`'s `service_apartment`), extending the
service-address snapshot from 4 to the full 7 fields already established on `users.default_*`
(`V20`). See `docs/architecture/data-model.md` §2.9.

**Active Booking Floating Indicator feature added one further migration to `orders`**:
`V23__alter_orders_add_expected_arrival_at.sql` (`expected_arrival_at TIMESTAMP`, nullable —
`NULL` for every order that never reached `ON_THE_WAY`; set exactly once, atomically, at the
`ON_THE_WAY` transition, never modified by any later transition). This is a single
absolute-timestamp column rather than a second `onTheWayAt` + `etaMinutes` pair — it's
exactly what the frontend countdown needs (`remainingTime = expectedArrivalAt - now`), and
`orders.updated_at` already gets overwritten by later transitions (complete/cancel), so it
can't double as "when did on-the-way happen." See `docs/architecture/data-model.md` §2.9 and
`docs/architecture/active-booking-floating-indicator.md` §1.1 for the full column spec.

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
  identically by `listProfessionals`/`listAvailableWindows` (renamed from `listSlots` as of
  the professional weekly availability calendar M2)/`createOrder` (Standard,
  `urgencyType != STANDARD`) and `listSosProfessionals`/`createSosOrder` (SOS,
  `urgencyType != SOS`), consistent with the contract doc's framing of this as one symmetric
  fix (§3.10) rather than two unrelated additions.
- **(Superseded 2026-08-21 — the issue is now reopened, not expired; see the Status
  section.)** **`issues.status` transitions to `EXPIRED` unconditionally when `expireIfPending`'s guarded
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
- **Milestone 8 — `sort=RECOMMENDED`/`FASTEST` are necessarily in-memory re-sorts, not a
  second SQL query variant.** `averageRating`/`reviewCount` are read via correlated subqueries
  and `etaMinutes` is computed in Java, never persisted, so no single `ORDER BY` could sort by
  either at the DB level; every `sort` value fetches the identical DB-level `base_price
  ASC`-ordered result set and enrich every card identically — `sort` only changes the *final*
  ordering step, never which cards are enriched or how. `RECOMMENDED` orders by `averageRating`
  descending (a `null` average — no reviews yet — sorts last), tiebroken by `reviewCount`
  descending. `parseSort(String, ProfessionalSort)` takes an explicit default per call site
  rather than a single hardcoded one, but **both** the Standard and SOS listings pass
  `ProfessionalSort.CHEAPEST` as that default when `sort` is blank/omitted. Invalid non-blank
  `sort` values still `400 VALIDATION_ERROR`, message "must be one of CHEAPEST, RECOMMENDED,
  FASTEST".
- **MS3/MS4 product-corrections pass (2026-08-17) — sort-toggle scope reconciled.** The
  `RECOMMENDED` ranking above is a genuinely new mode (not a relabel of `FASTEST`), added along
  with the enum's third value during this pass. On the frontend, both `BookingFlowPage`
  (Standard) and `SosBookingFlowPage` (SOS) expose an **identical 2-way `Recommended |
  Cheapest` chip toggle** (`STANDARD_SORT_OPTIONS`/`SOS_SORT_OPTIONS` in
  `frontend/src/features/professionals/ProfessionalList.tsx`, both `[RECOMMENDED, CHEAPEST]`,
  Recommended shown first) — grounded in `frontend/Pronto — DESIGN_SYSTEM.md` §31-34 and the
  user's own correction-spec wording ("Recommended... and Cheapest"). `FASTEST` is **not**
  dropped from the backend — it stays a valid `ProfessionalSort` enum value with working
  ranking logic, reachable via a direct API call, kept dormant for a possible future SOS-specific
  enhancement — it is simply not wired to any chip in either flow this pass. An earlier,
  uncommitted draft of this same work briefly gave the SOS listing a `FASTEST` default and a
  `Recommended | Fastest` chip pair (with `Cheapest` dropped from SOS); that draft was not
  authorized and was reconciled back to the state described here before the corrections branch
  was finalized. See `docs/architecture/ms3-ms4-corrections-design.md` §3 for the full
  reconciliation record and `docs/architecture/api-contract-professionals-reviews.md` §7.2 for
  the corrected API-contract-level description.
- **MS3/MS4 product-corrections pass — `orders` service-address snapshot extended to 7
  fields.** `V22__alter_orders_add_service_address_details.sql` adds `service_floor
  VARCHAR(20)`/`service_entrance VARCHAR(20)`/`service_address_notes VARCHAR(500)` to
  `orders`, matching the field set already established on `users.default_*` (`V20`). Nullable
  at the DB level (no backfillable source for existing orders) and optional at the API layer
  too (no `@NotBlank`), same convention as the pre-existing `serviceApartment`. `Order`,
  `CreateOrderRequest`/`CreateSosOrderRequest`, and `OrderResponse`/`OrderDetailResponse` all
  carry the 3 new fields alongside the original 4. `BookingsService`'s `createOrder`/
  `createSosOrder` persist whatever address fields arrive in the request body regardless of
  whether the frontend's `AddressSelectionStep` used the customer's saved default address or a
  one-off custom address — the "default vs. custom" distinction is a frontend/UX concern only;
  the backend contract is address-source-agnostic by design, with no `addressSource` field
  added anywhere.
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

- **Active Booking Floating Indicator feature — `expectedArrivalAt` is a single, immutable,
  absolute-timestamp column, computed once and never re-derived.** It is set exactly once,
  atomically alongside the `ON_THE_WAY` transition, and never touched by `complete`/`cancel`
  — a deliberate snapshot of "what we told the customer to expect" at that moment, not a
  live-recomputed figure that would drift if recomputed later. This narrowly overrides the
  previously-settled "ETA is never persisted" architectural ruling
  (`docs/architecture/overview.md` §2, `docs/architecture/data-model.md` §4) — flagged
  explicitly there and in `docs/architecture/active-booking-floating-indicator.md` §0.1,
  not silently reversed. The `matching` package itself is unchanged by this — see
  `matching/README.md`.
- **`onTheWay`'s ETA computation had zero test coverage before this feature** — confirmed by
  grepping `BookingsServiceTest.java` prior to this pass (`onTheWay`/`ON_THE_WAY` didn't
  appear at all). 3 new tests were added covering the ETA computation/persistence path
  (bounds-checked `expectedArrivalAt` derivation, the `orderNotConfirmed` failure path, and
  the caller-forbidden path never invoking `onTheWayIfConfirmed`).

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
not just asserts it). **Resolved, Milestone 7 (2026-08-15)**: the user ruled this
intentional, permanent design — `EXPIRED` stays a final `issues.status` state forever, no
reopen endpoint, no relaxed booking guard on `createOrder`/`createSosOrder`; a customer who
wants service again creates a new issue. See `docs/architecture/data-model.md` §4,
`docs/architecture/api-contract-notifications.md` §7, and
`docs/architecture/hardening-plan.md` §4.1 for that resolution record.

  **Reversed by explicit product decision, SOS final-readiness pass (2026-08-21.)** The
  Milestone 7 ruling made an unanswered order cost the customer everything they had already
  provided — description, photos, AI classification, address — because a *professional* failed
  to respond in fifteen minutes. `expireIfPending` now calls `IssueRepository.reopenIfBooked`
  (`BOOKED -> OPEN`) instead of the old `expireIfBooked` (`BOOKED -> EXPIRED`), so an expired
  order behaves exactly like a rejected or cancelled one: the order stays `EXPIRED` in history,
  its issue becomes bookable again, and the customer picks a different professional for the
  *same* issue (`/issues/{issueId}/booking`, reached from the tracking screen's
  "בחירת בעל מקצוע אחר" action). `IssueStatus.EXPIRED` is now written by no code path at all
  and is retained only so rows predating this change still map. The single-active-order
  invariant is untouched: `bookIfOpen` remains the only way out of `OPEN`, and only one caller
  can win it. **Slot
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

**MS3/MS4 product-corrections pass (2026-08-17)**: this package gained one new migration
(`V22`, the 3 remaining service-address fields — `serviceFloor`/`serviceEntrance`/
`serviceAddressNotes`, see "Data model" above) and the sort-toggle reconciliation described
under "Assumptions" above (`RECOMMENDED` ranking logic and the enum's third value are
unchanged/confirmed-correct; the SOS listing's `sort` default, which an out-of-scope,
unauthorized draft of this same work had briefly changed to `FASTEST`, was reverted to
`CHEAPEST` to match the Standard listing). No new `ErrorCode` values. Full change record:
`docs/architecture/ms3-ms4-corrections-design.md`.

**Active Booking Floating Indicator feature (2026-08-17)**: this package gained one new
migration (`V23`, `expected_arrival_at` on `orders`), one entity field
(`Order.expectedArrivalAt`, getter-only), a breaking signature change to
`OrderRepository.onTheWayIfConfirmed` (single caller, updated in lockstep), an ETA-computation
step inside `BookingsService.onTheWay`, and new fields across `OrderResponse`/
`OrderDetailResponse`/`OrderSummaryResponse` (`expectedArrivalAt` on all three, plus
`updatedAt` on `OrderSummaryResponse`) — see "Responsibilities"/"Key classes"/"Data
model"/"Assumptions" above for the full writeup. No new `ErrorCode` values, no new
`availability`/`notifications`/`issues` dependency edges. **QA-passed, 12/12 checklist items,
zero bugs found.** Full design/decision record:
`docs/architecture/active-booking-floating-indicator.md` (also narrowly overrides the
previously-settled "ETA is never persisted" ruling — see that doc's §0.1, and
`docs/architecture/overview.md` §2 / `docs/architecture/data-model.md` §4 for the recorded
override). This is a **separate, additive pass from the MS3/MS4 product-corrections pass
above** — not an extension of that pass's own still-partially-undocumented scope (address
selection, sort-toggle reconciliation, booking-draft indicator); see
`docs/architecture/ms3-ms4-corrections-design.md` for that separate, still-open documentation
item.

**Backend MS9 — presigned image URLs (2026-08-18)**: this package's `enrichAndSort` call
site swapped its `storage.client.StorageClient` dependency for `storage.service
.StorageService` (`getPresignedUrl(callerId, key)`, threading `callerId` through from
`listProfessionals`/`listSosProfessionals`) — see "Interactions" above. No entity/migration/
`ErrorCode` changes in this package; the substantive fix (permanent proxy URLs replaced by
time-limited presigned URLs, plus two related bug fixes) lives in `storage`/`issues` — see
those packages' READMEs and `docs/architecture/backend-ms9-presigned-image-urls-design.md`
for the full record. Backend: 163/163 tests pass.

## Professional weekly availability calendar — M2 (2026-08-18)

Full design: `docs/architecture/professional-weekly-calendar-design.md` §9.1/§9.2/§10 (M2
entry). M1 (schema/domain/derivation, entirely inside `availability`) landed first and is
unmodified by this pass — see `availability/README.md`.

**`POST /api/bookings/orders` — order-creation rework (§9.2.2).** `slotId` is removed from
`CreateOrderRequest` entirely (not kept, even as an optional/ignored field); `bookedStart`
(`@NotNull Instant`) replaces it. `bookedEnd` is never accepted from the client — always
`bookedStart + BookingsService.DEFAULT_JOB_DURATION_MINUTES` (a new `static final int = 60`
constant, carrying the same explicitly-flagged-placeholder-business-figure Javadoc treatment
this class already gives `SOS_SURCHARGE_AMOUNT`, since 60 minutes is a genuine product
decision made in the design doc itself, §9.2.1, with no source-document backing). New
validation path: (1) derive `bookedEnd`; (2) a fast pre-check via the new
`AvailabilityDerivationService` dependency — `deriveCalendar(professionalId, bookedStart,
bookedEnd)` must return a single `AVAILABLE` segment that fully contains the requested range,
else `409 BOOKING_TIME_UNAVAILABLE`; (3) the existing `issueRepository.bookIfOpen` transition,
unchanged; (4) insert the `orders` row with `slot_id = NULL` always; (5) the insert itself is
protected by `ck_orders_no_overlap` (M1's exclusion constraint) as the sole authoritative
concurrency backstop — `orderRepository.saveAndFlush` (not the plain `save` every other
creation path uses) forces the `INSERT` to execute inside this method's own `try/catch`, so a
`23P01` (exclusion-violation) `DataIntegrityViolationException` is caught and mapped to the
same `409 BOOKING_TIME_UNAVAILABLE` rather than surfacing as a raw `500` — mirrors
`AvailabilityService#mapBlockConstraintViolation`'s exact pattern (M1), duplicated here rather
than factored into a shared utility (no such utility exists yet in this codebase; inventing
one wasn't asked for by this milestone). `createSosOrder` — **confirmed untouched**, out of
scope per the design.

**`GET /api/bookings/professionals/{professionalId}/available-windows?issueId=` — new route,
replaces the retired `GET .../slots?issueId=` entirely** (old controller method and
`BookingsWebConfig` literal-path entry both removed, not kept for compatibility). Auth/role/
validation steps 1-5 are byte-for-byte identical to the old endpoint (caller role via
`BookingsWebConfig`, issue ownership, urgency-type match, bookable-status check, professional
existence, category match); only the final query changes — a call to
`AvailabilityDerivationService#deriveAvailableWindows(professionalId, from, to,
Duration.ofMinutes(DEFAULT_JOB_DURATION_MINUTES))`, with `from = now()` and `to = now() + 14
days` (a new private constant, `AVAILABLE_WINDOWS_LOOKAHEAD_DAYS` — an application-level,
trivially-adjustable judgment call per design §9.2.2, not specified by any source document).
Response: `{ professionalId, issueId, defaultDurationMinutes, timezone, windows: [{ startAt,
endAt }] }` (new DTOs, `dto.AvailableWindowsResponse`/`dto.AvailableWindow`, replacing the
retired `dto.SlotListingResponse`/`dto.SlotSummary`, both deleted outright). An empty
`windows` array is a valid `200`, not an error — unchanged semantics from the old empty
`slots` array case.

**`users.phone` (§9.1) — not part of this package's own scope, but this package is the sole
consumer of the new visibility rule.** `bookings.dto.OrderDetailResponse` gains
`customerPhone`, populated by `getOrderDetail`'s **existing, unmodified** party-to-order
authorization check — no new authorization branch, no `order_status` gating. The method
already loaded the customer's `User` row for `customerName`; `customerPhone` is simply also
read off that same row (`user.getPhone()`) once the existing check has passed. Visible to the
order's own customer and to the assigned professional starting at `PENDING` (the moment
`order.professionalId` names them), the same access-scoping the service-address snapshot
already uses. **Not** added to `OrderSummaryResponse` (list-mine) or `OrderResponse`
(create/accept/reject echo) — no stated use case on either, per the design's explicit scope
limit.

**New `ErrorCode`**: `BOOKING_TIME_UNAVAILABLE` (`409`), returned by both the pre-check and
the race-backstop catch above. `SLOT_UNAVAILABLE` stays in the enum — vestigial as of this
milestone, never returned by any code path once no caller can supply a `slotId` to
`createOrder` at all, kept per this doc family's "cheap insurance, zero ongoing cost"
convention (M1 already established this for `availability_slots`' own endpoints).

**No migration owned by this package** — `V28__alter_users_add_phone.sql` lives in `users`'
domain (adds `users.phone`), not `orders`. This package's own tables/columns are unchanged by
M2; only the request/response DTO shapes and the `createOrder`/listing service logic changed.

### Verification performed

Manually exercised against a real running backend + Postgres (`docker compose`, all 28
migrations applied cleanly): `CUSTOMER` registration without `phone` → `400
VALIDATION_ERROR`; with `phone` → persisted, returned on `GET /api/users/me`;
`PROFESSIONAL` registration unaffected, `users.phone` stays `NULL` for that role. Working
hours configured for a professional; `GET .../available-windows?issueId=` returned a
14-day-bounded set of `AVAILABLE` windows with `defaultDurationMinutes: 60`/`timezone:
"Asia/Jerusalem"`. `POST /api/bookings/orders` with a `bookedStart` inside an available
window → `201`, `bookedEnd = bookedStart + 60min`, `slot_id = NULL` on the persisted row
(confirmed via direct `psql`). Three independent `409 BOOKING_TIME_UNAVAILABLE` triggers
confirmed: overlapping an already-`PENDING` booking, outside configured working hours, and
overlapping a manual block. `GET /api/bookings/orders/{orderId}` confirmed `customerPhone`
visible to the assigned professional on a still-`PENDING` order. Concurrent-race simulation
(two near-simultaneous `createOrder` calls for the same professional with overlapping
`bookedStart`/`bookedEnd`) produced exactly one `201` and one clean `409
BOOKING_TIME_UNAVAILABLE` (confirmed via the backend log that the second request's `INSERT`
did trip the `ck_orders_no_overlap` exclusion constraint at the SQL level, not merely lost the
pre-check race) — no raw `500`. Unit-test additions: 11 new `BookingsServiceTest` cases
covering the reworked `createOrder` (happy path, full-containment pre-check failure,
partial-overlap pre-check failure, past-`bookedStart` rejection, the `23P01`-mapping race
backstop, and confirming a non-`23P01` constraint violation rethrows unmapped),
`listAvailableWindows` (mapping, empty result, category mismatch), and `getOrderDetail`'s
`customerPhone` field (assigned professional on a `PENDING` order, and the customer viewing
their own order), plus 1 new `AuthServiceTest` case confirming a `PROFESSIONAL` registration
never sets `users.phone` (the existing customer-registration success test was also extended
with a `phone`-persisted assertion, not counted as a new case). Full suite: 201/201 passing
(M1 baseline was 189/189; +12 from this milestone's additions).

### Deviation flagged, not silently worked around

While probing the retired route for a clean confirmation it no longer resolves,
`GET /api/bookings/professionals/{id}/slots?issueId=` (and, for comparison, an arbitrary
nonexistent path under `/api/**`) both return `500 INTERNAL_ERROR`, not `404 NOT_FOUND` — this
app's `common.exception.GlobalExceptionHandler` has no dedicated handler for Spring's
`NoResourceFoundException` (thrown when no controller mapping matches and MVC's static-resource
fallback also finds nothing), so it falls through to the generic unhandled-exception `500`
catch-all. **Confirmed this is pre-existing, app-wide behavior, not introduced by retiring this
specific route** — any never-mapped `/api/**` path exhibits the identical `500` today. Not
fixed as part of this milestone (out of scope — a global `GlobalExceptionHandler` gap
unrelated to the availability-calendar feature), flagged here for `pronto-lead` as a
worthwhile, narrow, separately-scoped follow-up (`NoResourceFoundException` → `404 NOT_FOUND`).

### QA sign-off (2026-08-18) — full feature, M1-M6, zero known open bugs

`pronto-qa` independently validated this package's M2 slice (the reworked `createOrder`
validation path, the new `available-windows` endpoint, `customerPhone` on
`OrderDetailResponse`, both `409 BOOKING_TIME_UNAVAILABLE` trigger paths, and the
concurrent-race backstop) plus the post-QA bug-fix pass documented in
`features/booking/README.md` (the conflicting-booking error banner never rendering — found,
fixed, and re-verified live via a genuine two-customer race). **Full sign-off, zero known
open bugs**, consistent with the "zero known open bugs" bar every prior milestone in this
project has been held to. See `docs/architecture/implementation-plan.md`'s "Professional
Weekly Availability Calendar" entry for the consolidated M1-M6 QA record across both
`backend` and `frontend`, and `availability/README.md`'s own QA sign-off note for the M1
slice.

## Production Roadmap MS1 — the eligibility gate on this package's paths (2026-08-22)

Three of MS1's six gated paths are here, and all three read the *same* rule —
`professionals.ProfessionalEligibility` — rather than expressing it themselves; if either
side re-implemented it in Java, the listing and the booking guard could disagree about the
same person, which is worse than either being wrong consistently. (1) **The Standard listing**:
`repository.ProfessionalListingRepository#listByCategory` concatenates
`ProfessionalEligibility.ELIGIBLE_JPQL` into its `WHERE`. This is the customer's discovery
surface and MS0 recorded that it had no approval filter of any kind — the pre-existing
`u.deletedAt IS NULL` clause stays outside the fragment, per that constant's alias/scope
contract. (2) **`GET .../professionals/{id}/available-windows`** and (3) **`POST
/api/bookings/orders`** both now go through the renamed `isProfessionalBookable` (formerly
`isProfessionalActive`), which ANDs the pre-existing soft-delete check with
`ProfessionalRepository#existsEligibleById`; available-windows previously called *neither*, so
a customer could page through the calendar of a deleted or unverified professional right up to
the moment the order was refused. Order creation reports the failure as `400 VALIDATION_ERROR`
on `professionalId` ("must reference an existing, bookable professional"), the same
body-field-reference convention it already used; available-windows reports it as
`404 NOT_FOUND` through the new `professionalNotBookable` helper — **identical code and
identical message for a nonexistent id and for an existing-but-ineligible one**, so the
endpoint cannot be used to learn that a particular professional exists but was rejected or has
not been verified, and the eligibility check deliberately runs *before* the category
comparison for the same reason. `404` rather than a new `409` because from the booking flow's
point of view there is nothing at that id to show a calendar for; the customer-facing "why"
belongs on the professional's profile response, which carries the neutral `bookable` flag
(D-G). Separately, `listMyOrders`'s bare `else` branch — which would have resolved every
non-`CUSTOMER` caller down the professional path once `UserRole.ADMIN` existed, refusing an
operator only incidentally by their having no professional profile — became an explicit
three-way branch that throws `403 FORBIDDEN`: "my orders" is meaningless for an operator, so
say so. **Live-flowing work is deliberately left ungated**: nothing here re-checks eligibility
on an order that already exists, so a professional who is rejected mid-job is not stranded
with an obligation they cannot complete. No migration, no new `ErrorCode`, no DTO shape change
in this package. Extended `bookings.service.BookingsServiceTest`; live-validated (MS1 report,
Validations 7–9: the Standard listing dropped from 30-of-30 professionals to 1-of-30 against
real baseline data, an `APPROVED`-but-incomplete professional is refused `400`, and
available-windows for an ineligible professional returns `404`).

## The listing is filtered by service coverage (the Eilat fix)

`GET /api/bookings/professionals` used to filter on category, approval, onboarding and phone
verification — and **no geography at all**. A customer in Eilat was shown every eligible
professional in the country; the only thing their address changed was the ETA printed on each card.
So the listing appeared to answer "who does this work, near me?" while actually answering "who does
this work?".

`BookingsService.listProfessionals` now resolves the customer's city to a canonical `service_cities`
id (`locations.service.ServiceCityResolver`) and passes it to
`ProfessionalListingRepository.listByCategoryAndServiceCity`, which applies
`professionals.ProfessionalServiceAreaMatch.SERVES_CITY_JPQL` — the same constant the SOS hard
filter uses, so the two surfaces cannot disagree about who serves where.

- **Coverage decides, not base city.** A Tel Aviv-based professional listing Eilat is eligible
  there; one listing only Gush Dan cities is not, however close their base city is.
- **Coverage decides, not distance.** ETA is computed from a live device position that may be
  anywhere and degrades to "unavailable" when routing is down. It ranks; it never gates.
- **All three sort modes share one candidate set.** `enrichAndSort` only ever reorders what the
  query returned, so RECOMMENDED/CHEAPEST/FASTEST cannot reintroduce an out-of-area professional.
- **An unresolvable city short-circuits to an empty listing**, with a
  `bookings.listing.uncovered` log line, rather than binding null and relying on SQL null semantics
  to filter everything out by accident.


## Service-address length bounds (2026-09-04)

`CreateOrderRequest`'s `serviceCity`, `serviceStreet` and `serviceAddressNotes` had no `@Size` at
all — the only three address strings in the codebase that didn't. They now carry 100/150/500,
the same widths as the sibling DTOs (`users.dto.CustomerAddressRequest`,
`auth.dto.DefaultAddressRequest`, `sos.dto.CreateSosRequestRequest`) and the `users.default_*`
columns they mirror. `serviceApartment`/`serviceFloor`/`serviceEntrance` were already bounded by
their `maps.AddressAccessFields` patterns (`\d{0,20}`, `\d{0,20}`, `[\p{L}0-9]{0,2}`).
