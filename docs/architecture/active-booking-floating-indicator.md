# Active Booking Floating Indicator — Design

**New file, not an append to `ms3-ms4-corrections-design.md`.** Reason: this is an
additive feature (a new app-shell component + a new backend field + a new route), not a
correction to that doc's 4 scoped items. Appending it there would blur that doc's title
("MS3/MS4 product corrections") and its own not-yet-committed scope. This doc instead
**depends on and cross-references** `ms3-ms4-corrections-design.md` §4 (the
`BookingDraftProvider`/`BookingDraftIndicator` pattern, assumed already landed/landing as
designed there) without re-explaining it.

Status: design only, not implemented. Grounded directly in the current repository state
(including the uncommitted `frontend/MS3-MS4-corrections` tree) as of 2026-08-17 — every
file/line referenced below was read, not assumed.

---

## 0. Two decisions this doc implements as given (not re-derived)

1. **ETA accuracy**: a real `expectedArrivalAt` column, computed once at the
   `POST /api/bookings/orders/{orderId}/on-the-way` transition, reusing
   `DistanceEtaStrategy.calculate(...)` (the exact same call `BookingsService.enrichAndSort`
   already makes for listing cards).
2. **Multiple simultaneous active bookings**: a customer can have more than one active
   order at once (the single-active-order invariant is per-**issue**, confirmed at
   `data-model.md` lines 533-537: *"they may still have another active order"*). Priority
   rule for the one floating indicator: **`ON_THE_WAY` > `CONFIRMED`/`PENDING` > unacknowledged
   `COMPLETED`.**

### 0.1 Important contradiction this supersedes — flagged explicitly, not silently resolved

`docs/architecture/overview.md` §2 (the **"Resolved decisions — settled, do not
re-litigate without a new contradiction"** table) has this exact row, dated 2026-08-15:

> **Professional-search distance/ETA** — "Now in v1.0 scope — dynamically computed
> (**never persisted**)... **Scoped to professional search/listing only — the tracking
> screen gained no new field**..."

And `backend/.../matching/EtaResult.java`'s javadoc: *"never persisted (approved design
§1 classification items 7-8)."* And `OrderTrackingPage.tsx`'s own code comment (still on
disk, uncommitted-tree version): *"no ETA field here (Milestone 8's `etaMinutes` lives
only on the professional-listing card, `OrderDetailResponse` has no such field — not
fabricated)."*

**This task's decision 1 (§0 above) directly overrides all three of these** — it adds a
real, persisted `expectedArrivalAt` and puts it on the tracking screen. This is a genuine,
previously-settled architectural ruling being reversed, not a detail I'm inferring. I'm
implementing it because the task brief explicitly instructs it as an already-made user
decision ("implement this, do not re-derive or second-guess it"), but per this agent's
own standing instructions I must surface it rather than silently let the old ruling and
the new code disagree. **Action needed**: `pronto-documentation`/`pronto-lead` should
update `overview.md` §2's row (append the override, same convention already used twice in
that same row for its own prior override), plus `EtaResult.java`/`ServiceLocation.java`
javadoc, `api-contract-professionals-reviews.md` §5, and the `OrderTrackingPage.tsx`
comment itself (superseded, not deleted-without-trace). Listed again in §7's docs list.

Note the override is narrow and consistent with the rest of the ruling: `matching`
package itself still computes/persists nothing (`EtaResult`/`ServiceLocation` remain
pure, stateless, no table of their own) — it's the **caller** (`bookings`) that now
persists the *result* of one specific `calculate()` call, once, at one specific state
transition. GPS/live-location tracking (the separate, still fully valid exclusion) is
untouched.

---

## 1. Backend: migration + entity + repository

### 1.1 Migration — **V23** (verified: current max on disk, including the uncommitted
tree, is `V22__alter_orders_add_service_address_details.sql`)

`backend/src/main/resources/db/migration/V23__alter_orders_add_expected_arrival_at.sql`
(new file):

```sql
-- Persists the ETA computed at the moment a professional marks an order ON_THE_WAY
-- (BookingsService.onTheWay), reusing DistanceEtaStrategy.calculate -- the same
-- strategy BookingsService.enrichAndSort already calls for listing-card ETA. Supersedes
-- the prior "ETA is never persisted, tracking screen gains no new field" ruling
-- (overview.md §2 professional-search-distance/ETA row, 2026-08-15) -- see
-- docs/architecture/active-booking-floating-indicator.md §0.1.
--
-- Nullable: null for every order that never reached ON_THE_WAY (PENDING/CONFIRMED, or an
-- order that went CONFIRMED -> CANCELLED/REJECTED without ever going ON_THE_WAY). Set
-- exactly once, at the ON_THE_WAY transition, and never modified by any later transition
-- (complete/cancel) -- an immutable snapshot of "what we told the customer to expect,"
-- not a live-recomputed figure.
ALTER TABLE orders ADD COLUMN expected_arrival_at TIMESTAMP;
```

Single column. (The brief allowed either `expectedArrivalAt` alone or
`onTheWayAt` + minutes — decided on the single absolute-timestamp column: it's exactly
what the frontend countdown needs [`remainingTime = expectedArrivalAt - now`], and
`orders.updated_at` already gets overwritten by later transitions [complete/cancel], so it
cannot double as "when did on-the-way happen" — a second column would be redundant with
what a single persisted absolute timestamp already gives us.)

### 1.2 `Order` entity — edit

Add, mirroring the existing `orderStatus`/`cancelledBy` convention exactly (**no setter**
— this class's own javadoc already states state-transition fields are never
loaded-mutated-saved, only written via the repository's atomic guarded `UPDATE`s; callers
reload via `findById` afterward):

```java
@Column(name = "expected_arrival_at")
private Instant expectedArrivalAt;
```
```java
public Instant getExpectedArrivalAt() {
    return expectedArrivalAt;
}
```

Not part of the constructor (always `null` at order-creation time, same as `cancelledBy`
starting `null`).

### 1.3 `OrderRepository.onTheWayIfConfirmed` — edit (signature change)

Extend the existing guarded `UPDATE` to also set the new column in the same atomic
statement — `expectedArrivalAt` is computed by the caller (`BookingsService`, a pure call
to `DistanceEtaStrategy`, no I/O) and passed in already-resolved, never computed inside
the repository:

```java
/**
 * §2.16 step 4, extended by the active-booking-floating-indicator design to also persist
 * the ETA the service layer already computed (DistanceEtaStrategy.calculate is a pure,
 * stateless call made in BookingsService.onTheWay, never inside this repository) at the
 * moment of transition. 0 affected rows means the order wasn't CONFIRMED.
 */
@Modifying(clearAutomatically = true)
@Query("UPDATE Order o SET o.orderStatus = com.pronto.bookings.entity.OrderStatus.ON_THE_WAY, "
        + "o.updatedAt = :now, o.expectedArrivalAt = :expectedArrivalAt "
        + "WHERE o.id = :orderId AND o.orderStatus = com.pronto.bookings.entity.OrderStatus.CONFIRMED")
int onTheWayIfConfirmed(@Param("orderId") Long orderId, @Param("now") Instant now,
                         @Param("expectedArrivalAt") Instant expectedArrivalAt);
```

This is a breaking signature change to an existing method — there is exactly one caller
(`BookingsService.onTheWay`), updated in lockstep below, so no dangling call sites.

### 1.4 `BookingsService.onTheWay` — edit

```java
@Transactional
public OrderResponse onTheWay(Long callerId, Long orderId) {
    Order order = loadOrder(orderId);
    Long professionalId = resolveProfessionalId(callerId);
    if (!order.getProfessionalId().equals(professionalId)) {
        throw forbidden();
    }

    Instant now = Instant.now();
    Professional professional = professionalRepository.findById(professionalId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                    "Professional " + professionalId + " not found."));
    ServiceLocation customerLocation = new ServiceLocation(order.getServiceCity(), order.getServiceStreet(),
            order.getServiceHouseNumber(), order.getServiceApartment());
    EtaResult eta = distanceEtaStrategy.calculate(professional.getCity(), customerLocation, now);
    Instant expectedArrivalAt = now.plus(Duration.ofMinutes(eta.etaMinutes()));

    int affected = orderRepository.onTheWayIfConfirmed(orderId, now, expectedArrivalAt);
    if (affected == 0) {
        throw orderNotConfirmed(orderId);
    }
    // issues.status is not touched -- stays BOOKED (§2.16 step 5, unchanged).
    notificationService.recordOrderNotification(orderId, order.getCustomerId(),
            NotificationMessageType.ORDER_ON_THE_WAY);
    return toOrderResponse(loadOrder(orderId));
}
```

Notes:
- `ServiceLocation`/`EtaResult`/`DistanceEtaStrategy`/`Duration` are all already imported
  in `BookingsService.java` (verified by reading the file — `enrichAndSort` already uses
  the first three; `Duration` is already imported for `STANDARD_PENDING_TIMEOUT`/
  `SOS_PENDING_TIMEOUT`).
- The extra `professionalRepository.findById(professionalId)` lookup mirrors the existing
  `resolveProfessionalName` helper's own "second lookup by id" pattern already present in
  this same class — an accepted, precedented style, not a new one.
- `order.getServiceCity()/getServiceStreet()/getServiceHouseNumber()/getServiceApartment()`
  is exactly the service-address snapshot already stored on the order at creation time
  (§1 classification item 5) — no new input needed, no new request body field on the
  `on-the-way` endpoint.

### 1.5 `toOrderResponse` / `getOrderDetail` / `listMine` — edit

All three need `order.getExpectedArrivalAt()` threaded into their respective response
constructors (exact positions given by the DTO shapes in §1.6).

---

## 2. Backend DTO changes

### 2.1 `OrderResponse` — edit (field added after `bookedEnd`)

```java
public record OrderResponse(
        Long id,
        Long issueId,
        Long customerId,
        Long professionalId,
        OrderStatus orderStatus,
        Instant bookedStart,
        Instant bookedEnd,
        Instant expectedArrivalAt,
        BigDecimal finalPrice,
        BigDecimal basePriceSnapshot,
        BigDecimal sosSurcharge,
        String serviceCity,
        String serviceStreet,
        String serviceHouseNumber,
        String serviceApartment,
        String serviceFloor,
        String serviceEntrance,
        String serviceAddressNotes,
        CancelledBy cancelledBy,
        Instant createdAt,
        Instant updatedAt
) {
}
```

(No frontend caller consumes this today for the on-the-way response specifically — there
is currently **no `onTheWay`/`complete` client-side API function at all** in
`frontend/src/shared/api/bookings.ts`, confirmed by reading it; professional-side
accept/reject/on-the-way/complete action buttons are explicitly "not built here" per
`features/dashboard/README.md`. Added anyway for shape-consistency with
`OrderDetailResponse` — same convention this record already follows for every other
field. Building the professional-side action buttons is out of this doc's scope; flagged
as a pre-existing gap, not something this design closes.)

### 2.2 `OrderDetailResponse` — edit (identical field addition, same position)

```java
public record OrderDetailResponse(
        Long id,
        Long issueId,
        Long customerId,
        String customerName,
        Long professionalId,
        String professionalName,
        OrderStatus orderStatus,
        Instant bookedStart,
        Instant bookedEnd,
        Instant expectedArrivalAt,
        BigDecimal finalPrice,
        BigDecimal basePriceSnapshot,
        BigDecimal sosSurcharge,
        String serviceCity,
        String serviceStreet,
        String serviceHouseNumber,
        String serviceApartment,
        String serviceFloor,
        String serviceEntrance,
        String serviceAddressNotes,
        CancelledBy cancelledBy,
        Instant createdAt,
        Instant updatedAt
) {
}
```

This is the DTO `GET /api/bookings/orders/{orderId}` returns — i.e. what
`useOrderStatus`/`OrderTrackingPage` polls. **Required** so the tracking screen can show
the ON_THE_WAY countdown (§4 below).

### 2.3 `OrderSummaryResponse` — edit, **yes, it needs the field** (firm answer, not left
open)

```java
public record OrderSummaryResponse(
        Long id,
        Long issueId,
        OrderStatus orderStatus,
        Instant bookedStart,
        Instant bookedEnd,
        Instant expectedArrivalAt,
        BigDecimal finalPrice,
        Instant createdAt,
        Instant updatedAt
) {
}
```

Two additions: `expectedArrivalAt` (needed by the indicator's countdown display — see §3
for why this DTO, not a detail-poll, is what the indicator reads) and **`updatedAt`**
(not previously on this record at all — needed as the tie-break key when more than one
`COMPLETED`-unacknowledged order is a candidate simultaneously, §5's algorithm; `createdAt`
alone can't express "most recently *completed*"). `listMine`'s stream-mapping call site
updates accordingly.

---

## 3. Sync mechanism — firm decision: **list-poll only**, no detail-poll for the indicator

**Decision: the app-level indicator polls `GET /api/bookings/orders/me`
(`getMyOrders`) only.** It never calls `GET /api/bookings/orders/{orderId}` itself.
Reasoning:

1. The priority-selection rule (§5) must run **across every one of the customer's
   orders** (decision 2 — multiple simultaneous active orders), which only the list
   endpoint can supply in one call. A per-order detail-poll can't do this by itself.
2. Once `expectedArrivalAt`/`updatedAt` are added to `OrderSummaryResponse` (§2.3), that
   lean list DTO already contains **100% of what the compact floating indicator needs to
   render all 3 states** — `orderStatus` (state selection), `expectedArrivalAt` (countdown
   math), `id` (click-through route). It does *not* need `customerName`/
   `professionalName`/full address/price breakdown — those only appear once the customer
   clicks through to the full tracking page or the completion/review page, both of which
   already independently fetch their own detail (`useOrderStatus`/`getOrder`,
   unchanged). A follow-up detail-poll from the indicator itself would be fetching data
   the indicator never displays — list-then-detail was considered and rejected as
   unjustified extra network traffic for no rendering benefit.
3. This is not a second, competing polling *mechanism* — it's a second **instance** of the
   same `usePolling` primitive the codebase already standardizes on (`overview.md` §3.3),
   at a different granularity than `useOrderStatus`'s existing per-order detail-poll. Both
   coexist for different purposes (see §6.3 for the explicit non-overlap statement) — this
   is the intended, singular short-polling convention applied twice for two different
   pieces of UI, not WebSocket, not a bespoke fetch loop.

---

## 4. `OrderStatus` → indicator-state mapping (explicit, all 7 values)

| `orderStatus` | Candidate for the indicator? | Indicator state if selected |
|---|---|---|
| `PENDING` | Yes | `PENDING_CONFIRMED` |
| `CONFIRMED` | Yes | `PENDING_CONFIRMED` |
| `ON_THE_WAY` | Yes | `ON_THE_WAY` |
| `COMPLETED`, not yet acknowledged (§6) | Yes | `COMPLETED_UNACKNOWLEDGED` |
| `COMPLETED`, already acknowledged | **No** — filtered out before priority selection runs | — |
| `CANCELLED` | **No — explicitly excluded from the candidate set** | — |
| `REJECTED` | **No — explicitly excluded from the candidate set** | — |
| `EXPIRED` | **No — explicitly excluded from the candidate set** | — |

`CANCELLED`/`REJECTED`/`EXPIRED` are terminal-and-uninteresting states for this feature:
confirmed explicitly here (per the brief's request not to leave this unstated) — they
never produce an indicator, are never part of the priority-selection candidate list at
all (not even as a lowest-priority fallback), and require no acknowledgement mechanism
since there is nothing to acknowledge.

---

## 5. Priority-selection algorithm

Pure function, lives in `frontend/src/shared/hooks/activeOrderContext.ts` (mirrors
`bookingDraftContext.ts`'s existing `resolveDraftRoute` pure-helper-next-to-the-context
convention) — **no business logic duplicated inside the indicator component**, per the
brief's explicit requirement.

```ts
export type ActiveOrderIndicatorState = 'PENDING_CONFIRMED' | 'ON_THE_WAY' | 'COMPLETED_UNACKNOWLEDGED';

export interface ActiveOrderSelection {
  order: OrderSummary;
  state: ActiveOrderIndicatorState;
}

/**
 * Decision 2's priority rule: ON_THE_WAY > CONFIRMED/PENDING > unacknowledged COMPLETED.
 * CANCELLED/REJECTED/EXPIRED are excluded from the candidate set entirely (§4). Tie-break
 * within a tier is this design's own recommendation (not specified by any source
 * document) -- flagged as such, easy to change: soonest-arriving first for ON_THE_WAY
 * (most useful to surface), most-recently-created first for PENDING/CONFIRMED, most-
 * recently-completed (updatedAt) first for COMPLETED_UNACKNOWLEDGED.
 */
export function selectActiveOrder(
  orders: OrderSummary[],
  acknowledgedOrderIds: number[],
): ActiveOrderSelection | null {
  const onTheWay = orders.filter((o) => o.orderStatus === 'ON_THE_WAY');
  if (onTheWay.length > 0) {
    const soonest = [...onTheWay].sort((a, b) =>
      (a.expectedArrivalAt ?? '').localeCompare(b.expectedArrivalAt ?? ''),
    )[0];
    return { order: soonest, state: 'ON_THE_WAY' };
  }

  const pendingConfirmed = orders.filter((o) => o.orderStatus === 'PENDING' || o.orderStatus === 'CONFIRMED');
  if (pendingConfirmed.length > 0) {
    const mostRecent = [...pendingConfirmed].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
    return { order: mostRecent, state: 'PENDING_CONFIRMED' };
  }

  const unacknowledgedCompleted = orders.filter(
    (o) => o.orderStatus === 'COMPLETED' && !acknowledgedOrderIds.includes(o.id),
  );
  if (unacknowledgedCompleted.length > 0) {
    const mostRecentlyCompleted = [...unacknowledgedCompleted].sort((a, b) =>
      b.updatedAt.localeCompare(a.updatedAt),
    )[0];
    return { order: mostRecentlyCompleted, state: 'COMPLETED_UNACKNOWLEDGED' };
  }

  return null;
}
```

---

## 6. Acknowledgement-tracking mechanism

### 6.1 localStorage key/shape

```
Key: pronto_ack_completed_orders
Shape: { ownerId: number; orderIds: number[] }
```

Naming/shape mirrors the existing `pronto_auth_token` (`AuthProvider.tsx`) /
`pronto_booking_draft` (`bookingDraftContext.ts`) conventions exactly — `pronto_` prefix,
and an `ownerId`-scoped object (not a per-user-suffixed key), same cross-account-leakage
guard pattern as `BookingDraftProvider`'s existing `useEffect` (§4.6 of
`ms3-ms4-corrections-design.md`): on mount, and whenever `useAuth().user` changes, if
`stored.ownerId !== user.id` (or `user` is `null` — logout), the whole record is cleared
(not merged/reconciled) — same rationale as the draft's guard: localStorage isn't
inherently user-scoped, and a shared/family browser must not let one account's
acknowledgement state (or, worse, its implied booking-status visibility) leak into
another's session.

### 6.2 The exact two actions that set it

1. **`CompletionReviewPage` mount** (a `useEffect` that fires once the page has
   confirmed, via its own `getOrder(orderId)` fetch, that the order really is
   `COMPLETED` — see §8) — this is "the customer opened/acknowledged the completion
   state," satisfied by merely *viewing* the screen, per the brief's explicit "review is
   NOT mandatory to consider the booking complete" instruction.
2. **After a successful `POST /api/reviews` response** inside the same page's submit
   handler — redundant with (1) in this design (the order is already marked acknowledged
   the moment the screen was opened), but implemented as its own explicit call anyway
   per the brief's literal "exactly which two actions set it" requirement, and because it
   is not truly dead code: `acknowledgeOrder` is idempotent (a no-op if the id is already
   present), so calling it again after submit costs nothing and keeps the two triggers
   independently correct if #1's mount-guard is ever changed later (e.g. if a future
   revision only acknowledges on an explicit "עדיין לא, תזכירו לי אחר כך" dismissal rather
   than on mere mount) without silently losing acknowledge-on-submit as a fallback.

### 6.3 Draft-indicator vs. active-order-indicator — explicit non-overlap

Structural, not just asserted:

- **Data-level**: a `BookingDraft` (client-only, localStorage, no backend row) and an
  `Order` (backend row, only created by `POST /api/bookings/orders`/`sos-orders`) can
  never both represent the *same* booking at the same time, because `BookingFlowPage`/
  `SosBookingFlowPage`'s existing `handleConfirmed(order)` calls `clearDraft()` in the
  exact same handler that receives the newly-created order (§4.5.1 of
  `ms3-ms4-corrections-design.md`, already confirmed/unchanged by this doc) — there is no
  window where a draft still exists for a booking that also now has a real order row.
- **They CAN both be visible on screen simultaneously** for **two different** bookings
  (e.g. an old completed order still awaiting review-acknowledgement, while the customer
  is mid-draft on a brand new, unrelated issue) — this is expected and correct, not a
  conflict; each indicator only ever describes its own referent.
- **Component/placement-level** (§7's `AppLayout.tsx` edit): `BookingDraftIndicator`
  stays exactly where it is today — an inline chip inside `<nav className={styles.nav}>`,
  alongside the profile/logout links. `ActiveOrderIndicator` is **not** added to that
  `<nav>` block at all — it's rendered as a separate, `position: fixed` floating circular
  element (a sibling of `<main>` in `AppLayout`'s returned tree, so it overlays every
  routed page, per the brief's literal "floating circular indicator" wording), gated on
  `user?.role === 'CUSTOMER'`. Two visually and structurally distinct components, each
  reading its own context (`useBookingDraft()` vs. `useActiveOrder()`), never merged into
  one.

---

## 7. New/changed files

**Backend**
- New: `backend/src/main/resources/db/migration/V23__alter_orders_add_expected_arrival_at.sql`
- Edit: `backend/src/main/java/com/pronto/bookings/entity/Order.java`
- Edit: `backend/src/main/java/com/pronto/bookings/repository/OrderRepository.java`
- Edit: `backend/src/main/java/com/pronto/bookings/service/BookingsService.java`
- Edit: `backend/src/main/java/com/pronto/bookings/dto/OrderResponse.java`
- Edit: `backend/src/main/java/com/pronto/bookings/dto/OrderDetailResponse.java`
- Edit: `backend/src/main/java/com/pronto/bookings/dto/OrderSummaryResponse.java`
- Edit: `backend/src/test/java/com/pronto/bookings/service/BookingsServiceTest.java` (new/
  updated coverage for `onTheWay`'s ETA computation/persistence — no such test exists
  today; confirmed by grepping the file, `onTheWay`/`ON_THE_WAY` don't appear in it at
  all, so this endpoint currently has zero test coverage, pre-existing gap this change
  should not leave wider)
- **No change**: anything under `backend/src/main/java/com/pronto/reviews/` — `POST
  /api/reviews` is reused completely as-is, confirmed already built end-to-end
  (role-gating, ownership, `COMPLETED`-only guard, duplicate-review guard all present).
- **No change**: `backend/src/main/java/com/pronto/matching/` (still pure/stateless, per
  §0.1's note — only its *caller* now persists a result).

**Frontend**
- New: `frontend/src/shared/api/reviews.ts` — `CreateReviewRequest`/`ReviewResponse`
  types + `createReview(payload)` wrapping `POST /api/reviews`. (First frontend consumer
  of this endpoint — confirmed by grepping `frontend/src` for `reviews`/`rating`: no
  review API client and no rating-input UI exist anywhere today.)
- New: `frontend/src/shared/hooks/activeOrderContext.ts` — types (`ActiveOrderIndicatorState`,
  `ActiveOrderContextValue`) + `createContext` + the pure `selectActiveOrder` (§5) +
  `resolveActiveOrderRoute` helpers.
- New: `frontend/src/shared/hooks/ActiveOrderProvider.tsx` — provider: polls
  `getMyOrders()` via `usePolling` (enabled only for an authenticated `CUSTOMER`),
  owns the acknowledged-ids state + localStorage read/write/cross-account guard (§6),
  runs `selectActiveOrder`, exposes `{ selection, acknowledgeOrder, refetch }`.
- New: `frontend/src/shared/hooks/useActiveOrder.ts` — thin `useContext` hook (mirrors
  `useBookingDraft.ts` exactly).
- New: `frontend/src/shared/hooks/useEtaCountdown.ts` — pure presentational hook: given
  `expectedArrivalAt: string | null`, ticks every 1s, returns `{ remainingMinutes: number
  | null; isArriving: boolean }`, always recomputed from `Date.now()` vs. the persisted
  absolute timestamp (never a locally-decremented counter — survives remount/refresh by
  construction). Shared by both `ActiveOrderIndicator` and `OrderTrackingPage`.
- Edit: `frontend/src/shared/hooks/index.ts` — barrel additions for the 3 new hook files.
- Edit: `frontend/src/shared/api/bookings.ts` — add `expectedArrivalAt: string | null` to
  `OrderResponse`/`OrderDetailResponse`/`OrderSummary`; add `updatedAt: string` to
  `OrderSummary`.
- Edit: `frontend/src/shared/api/index.ts` — barrel additions for `reviews.ts`.
- New: `frontend/src/app/ActiveOrderIndicator.tsx` + `.module.css` — the floating
  circular component; reads `useActiveOrder()` + `useEtaCountdown()`; **no inline
  business logic** — just a `switch` on `selection.state` choosing icon/label/route,
  calling `useNavigate()` on click.
- Edit: `frontend/src/app/AppLayout.tsx` — mount `<ActiveOrderIndicator />` as a sibling
  of `<main>` (outside the `<nav>` block, per §6.3), gated on `user?.role === 'CUSTOMER'`.
- Edit: `frontend/src/app/App.tsx` — wrap with `<ActiveOrderProvider>`, nested inside
  `<AuthProvider>` alongside (sibling-nested with) `<BookingDraftProvider>`, since it also
  needs `useAuth()` internally.
- New: `frontend/src/features/booking/CompletionReviewPage.tsx` + `.module.css` — new
  route component (§8); one-shot `getOrder(orderId)` fetch (no polling — the order is
  already terminal, nothing will change while this screen is open); a small local
  star-rating input (5 `lucide-react` `Star` icons, same icon already used in
  `ProfessionalCard.tsx` for rating display — kept local to this file, not promoted to
  `shared/components`, since there is exactly one consumer today, matching this
  codebase's own stated `shared/components` = "generic, multiply-reused primitives"
  convention); calls `acknowledgeOrder` per §6.2; calls `createReview`; handles `409
  REVIEW_ALREADY_EXISTS` gracefully (re-visiting an already-reviewed order — shows an
  "already reviewed" state rather than erroring) and `409 REVIEW_ORDER_NOT_COMPLETED`
  defensively (guards against a stale/direct-navigation edge case), same
  known-error-code-map pattern as `OrderTrackingPage.tsx`'s existing `CANCEL_ERROR_MESSAGES`.
- Edit: `frontend/src/features/booking/index.ts` — export `CompletionReviewPage`.
- Edit: `frontend/src/features/booking/OrderTrackingPage.tsx` — (a) render the
  ON_THE_WAY countdown via `useEtaCountdown(order.expectedArrivalAt)` when
  `order.orderStatus === 'ON_THE_WAY'`; (b) render a "השאירו ביקורת" (leave a review)
  link to `/orders/{id}/review` when `order.orderStatus === 'COMPLETED'` — **new,
  beyond the floating indicator itself**, see §9 for why this is necessary, not
  scope-creep; (c) remove/update the file's own stale "no ETA field here... not
  fabricated" comment (§0.1's supersession).
- Edit: `frontend/src/app/router.tsx` — new route (§8).

---

## 8. New route

**`/orders/:orderId/review`** — added under the existing `RequireAuth role="CUSTOMER"`
group (not the generic authenticated group `/orders/:orderId` sits in today), since
`POST /api/reviews` is `CUSTOMER`-only server-side (`ReviewsWebConfig`) and there is no
professional-side use for this screen:

```tsx
{
  element: <RequireAuth role="CUSTOMER" />,
  children: [
    { path: 'issues/new', element: <NewIssuePage /> },
    { path: 'issues/:issueId/booking', element: <BookingFlowPage /> },
    { path: 'issues/:issueId/sos-booking', element: <SosBookingFlowPage /> },
    { path: 'orders', element: <MyOrdersPage /> },
    { path: 'orders/:orderId/review', element: <CompletionReviewPage /> }, // new
  ],
},
```

Path convention (`/orders/:orderId/review`, nested under the existing `/orders/:orderId`
prefix rather than a flat `/reviews/new?orderId=`) matches this router's existing nesting
style (`issues/:issueId/booking`, `issues/:issueId/sos-booking`).

`CompletionReviewPage` itself fetches `getOrder(orderId)` on mount and guards on
`orderStatus === 'COMPLETED'` before rendering the review form — reachable both via the
floating indicator (only when it is the currently-selected order) **and** via direct
navigation/the `OrderTrackingPage` link added in §7/§9, so it must be defensively correct
standalone, not assume it was only ever reached from the indicator.

---

## 9. Why `OrderTrackingPage` also needs a "leave a review" link (flagged addition beyond
the literal ask)

The floating indicator shows **at most one** order at a time (§5). If a customer has two
`COMPLETED`-and-unacknowledged orders simultaneously (plausible under decision 2 — e.g.
two different issues both wrapped up close together), only the *higher-priority* one
(most recently completed, per this doc's tie-break) ever occupies the indicator slot. The
other one would otherwise have **no UI path to its review screen at all** — it never
"loses" its unacknowledged status (nothing ever un-marks it), it simply never wins the
single slot. Since every order (regardless of status) is already reachable via
`MyOrdersPage` → `/orders/:id`, adding the review link on `OrderTrackingPage` itself for
`COMPLETED` orders closes this gap with no new screen and no new API call. This is my own
recommendation, not literally spelled out in the brief — flagged as such, not silently
assumed; the alternative (leaving those orders permanently unreachable for review) seemed
clearly worse and directly follows from decision 2 once multiple simultaneous completed-
unacknowledged orders are possible.

---

## 10. Contradictions / open questions — explicit, not silently resolved

1. **§0.1 above** — the `overview.md` §2 "ETA never persisted / tracking screen gains no
   new field" resolved-decision row is directly overridden by this design. Implemented
   per this task's explicit instruction to treat decision 1 as already made; flagged for
   `pronto-lead`/`pronto-documentation` to formally record the override in `overview.md`
   (same convention that row already uses for its own prior override), not left as a
   silent contradiction between code and docs.
2. **Tie-break rules within a priority tier (§5)** — no source document specifies these
   (soonest-ETA / most-recently-created / most-recently-completed). This is this design's
   own recommendation, explicitly flagged as such, not a settled requirement — easy to
   change without touching the rest of the architecture if `pronto-lead`/the user wants a
   different tie-break.
3. **§9's `OrderTrackingPage` review-link addition** — not literally requested by the
   brief; added because decision 2 (multiple simultaneous active orders) makes it a real
   reachability gap otherwise, not because I inferred extra scope unprompted. Flagged for
   sign-off rather than silently shipped as if it were part of the original ask.
4. **Indicator visual copy/exact styling** (floating-button corner, colors, icon choices)
   is left to implementation/`DESIGN_SYSTEM.md` conventions — out of this architecture
   doc's scope, not a product decision requiring sign-off.
5. **No change proposed to `BookingsServiceTest.java`'s or `ReviewsServiceTest.java`'s
   existing (uncommitted) test edits** — read both files' git status only, did not open
   their full diffs since neither touches `onTheWay`/`expectedArrivalAt`; flagged in case
   `pronto-coding` finds an actual conflict once implementing, since this doc did not do a
   line-by-line diff review of those two test files.

---

## 11. Docs to update (not done here — for `pronto-documentation`)

- `docs/architecture/overview.md` §2 (professional-search-distance/ETA row — record the
  override, §0.1)
- `docs/architecture/data-model.md` §2.9 (new `expected_arrival_at` column) and §4 (if
  it's where the original "never persisted" ETA ruling lives — confirm exact location)
- `docs/architecture/api-contract-bookings.md` §2.16 (response shape), §2.8/§2.9 (response
  shapes), §2.4-2.6 (response shape, if reused there too)
- `docs/architecture/api-contract-professionals-reviews.md` §5 (ETA scope note)
- `backend/src/main/java/com/pronto/bookings/README.md`
- `backend/src/main/java/com/pronto/matching/README.md` (note its result can now be
  persisted by a caller, even though the package itself still persists nothing)
- `backend/src/main/java/com/pronto/reviews/README.md` (first real frontend consumer)
- `frontend/src/shared/hooks/README.md`
- `frontend/src/shared/api/README.md`
- `frontend/src/app/README.md`
- `frontend/src/features/booking/README.md`
