# `bookings`

## Purpose

`Orders` — Standard + SOS booking flows, accept/reject, and status transitions.

Implements `docs/architecture/api-contract-bookings.md` §2.2-2.9 (Standard path,
Milestone 3). SOS (Milestone 4) is not built here.

## Responsibilities

- `GET /api/bookings/professionals?issueId=` — professional listing for a Standard booking,
  filtered by the issue's category, joined to `users` to exclude soft-deleted professionals,
  ordered `base_price ASC` (judgment call, §7 of the contract doc). Requires the issue to be
  owned by the caller and `status = OPEN` (`409 ISSUE_NOT_BOOKABLE` otherwise).
- `GET /api/bookings/professionals/{professionalId}/slots?issueId=` — one professional's
  open, future `availability_slots`, ordered by `start_time ASC`. Same
  issue-ownership/bookable checks as the listing endpoint, plus `400 CATEGORY_MISMATCH` if
  the professional's category doesn't match the issue's.
- `POST /api/bookings/orders` — creates the order. Atomically claims the chosen slot
  (`UPDATE availability_slots ... WHERE is_available = true AND start_time > now()`,
  `409 SLOT_UNAVAILABLE` on 0 affected rows) and transitions the issue
  `OPEN -> BOOKED` (`UPDATE issues ... WHERE status = 'OPEN'`, rolling back the slot claim
  too on 0 affected rows), all in one `@Transactional` method, before inserting the `orders`
  row (`order_status = PENDING`, `final_price` initialized from `professional.basePrice`,
  `slot_id` always set for a Standard order).
- `POST /api/bookings/orders/{orderId}/accept` — `PENDING -> CONFIRMED`, professional-owner
  only. `issues.status` is not touched (stays `BOOKED`).
- `POST /api/bookings/orders/{orderId}/reject` — `PENDING -> REJECTED`, professional-owner
  only. Releases the slot and reverts the issue to `OPEN` — the reject-return-to-list flow's
  server-side half.
- `POST /api/bookings/orders/{orderId}/cancel` — either party, state-dependent (`CUSTOMER`:
  `PENDING`/`CONFIRMED`/`ON_THE_WAY`; `PROFESSIONAL`: `CONFIRMED`/`ON_THE_WAY` only — a
  professional backing out of a still-`PENDING` order must use `reject` instead). No
  route-level role gate (either role may call this) — actor/authorization resolved entirely
  in `BookingsService` once the order is loaded.
- `GET /api/bookings/orders/{orderId}` — tracking/status endpoint, enriched with
  `customerName`/`professionalName`. Party-to-the-order authorization only (either role, no
  route-level gate).
- `GET /api/bookings/orders/me` — self-scoped list (customer's own orders, or a
  professional's incoming/past orders), optional `status` filter, no pagination.

## Key classes

| Class | Role |
|---|---|
| `entity.Order` | JPA entity for `orders`. Exposes no setters for `orderStatus`/`cancelledBy` — every transition goes through `repository.OrderRepository`'s atomic `UPDATE` methods, never a load-mutate-save round trip. |
| `entity.OrderStatus` / `entity.CancelledBy` | Enums mirroring `orders.order_status` (7 values, post-`V11`) / `orders.cancelled_by`. |
| `repository.OrderRepository` | `JpaRepository`, plus `acceptIfPending`/`rejectIfPending`/`cancelIfStatus` (the atomic guarded transitions, §3.2) and the self-listing/`latestOrder`/professional-authorization finder methods. |
| `repository.ProfessionalListingRepository` | A narrow, read-only `professionals` ⨝ `users` query (§2.2) projected into `dto.ProfessionalCard` — deliberately lives here, not in `professionals`, to avoid a reverse `professionals -> bookings` dependency (see its Javadoc). |
| `dto.*` | Wire shapes for all nine endpoints (§2.2-2.9) — `OrderResponse` is shared by create/accept/reject (identical shape, differing only in `orderStatus`/`cancelledBy`); `OrderDetailResponse`/`OrderSummaryResponse` are the richer/leaner shapes for get-by-id vs. list-mine, mirroring the pattern M1 used for `/api/users/me`. |
| `service.BookingsService` | All business logic for §2.2-2.9, including the atomic-transaction sequencing in `createOrder` and the actor/authorization resolution for `cancel`/`getOrderDetail`/`listMine`. |
| `controller.BookingsController` | `/api/bookings/professionals`, `/api/bookings/professionals/{id}/slots`, `/api/bookings/orders` (+ `/accept`/`/reject`/`/cancel`/`/{orderId}`/`/me`). Path/query ids are parsed manually so a malformed value produces this app's standard error envelope (`404` for a path id, `400 VALIDATION_ERROR` for a query id) rather than Spring's default type-mismatch handling. |
| `config.BookingsWebConfig` | Two separate, precisely-scoped `RoleRequiredInterceptor` registrations (`CUSTOMER` on the three customer-only routes, `PROFESSIONAL` on `accept`/`reject`) — no blanket pattern, since this package mixes roles per-route. Nothing registered for `cancel`/get-by-id/get-me (service-layer authorization only). |

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
  `AvailabilitySlot` entity for slot start/end times at order-creation time).
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`, including this
  milestone's five new codes: `ISSUE_NOT_BOOKABLE`, `CATEGORY_MISMATCH`,
  `SLOT_UNAVAILABLE`, `ORDER_NOT_PENDING`, `ORDER_NOT_CANCELLABLE`) and
  `RoleRequiredInterceptor`/`AuthenticatedUser`.
- Will trigger `notifications` on every status transition — not built yet (Milestone 5).

## Data model

Owns the `orders` table (see `docs/architecture/data-model.md` §2.9), as amended by this
milestone's `V11__alter_orders_status_add_rejected.sql` (adds the genuine 7th
`order_status` value, `REJECTED` — a pre-existing gap fix, decided independently of this
milestone) and `V12__add_slot_id_to_orders.sql` (adds the nullable `slot_id` FK →
`availability_slots(id)`, the sole slot-release lookup mechanism).

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

## Status

**Implemented and QA-validated, Milestone 3 (Standard booking flow)**, on branch `MS3`
(not yet merged to `main` — pending the user's own git operations), per
`docs/architecture/api-contract-bookings.md` and `docs/architecture/implementation-plan.md`.
QA live-validated all 8 endpoints against a real Postgres instance: the full happy-path
Standard flow (listing → slot pick → create → accept → tracking), the reject → return-to-list
branch (§4 of the contract doc), cancel edge cases (actor/state permission matrix in §2.7),
and ownership/role enforcement on every endpoint — **zero bugs found**, no regressions to
Milestone 0-2. See `docs/architecture/implementation-plan.md`'s Milestone 3 entry for the
full QA summary. SOS booking flow (Milestone 4), `ON_THE_WAY`/`COMPLETED` progression
(Milestone 6), and the `PENDING`-timeout expiry sweep (Milestone 5) are explicitly out of
this milestone's scope. Professional-viewing-issue-images (contract doc §6 item 3 / §7)
remains an unresolved open item, not built here or anywhere yet.
