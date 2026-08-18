# features/booking

## Purpose
The Standard and SOS booking flows: choosing a professional, confirming a booking, and
tracking its status.

## Responsibilities
- Standard flow: professional list with each professional's own price offer, booking
  confirmation.
- SOS flow: urgent-availability professional list (reuses `features/professionals`
  components with urgent filtering rather than a separate screen), SOS request handling,
  price breakdown with the flat SOS surcharge disclosed before confirmation.
- Accept/reject handling from the customer's perspective once a professional responds.
- Confirmation / tracking screen showing booking status (Pending, Confirmed, On the Way,
  Completed, Cancelled, Expired) — status only, no GPS/map (out of scope for v1.0). **As of
  the Active Booking Floating Indicator feature**: a live `expectedArrivalAt` countdown is
  now shown while `ON_THE_WAY` (a real, persisted backend field — supersedes the prior "no
  ETA field here" note, see below), and a "leave a review" link is shown once `COMPLETED`.
- Post-completion review submission (`CompletionReviewPage`, new — see below).
- **As of Frontend Milestone 6**: professional-side job-status progression actions
  ("mark on the way", "mark completed") on the same tracking screen — see below.

## Status
**Standard and SOS flows implemented.** Standard flow: Frontend Milestone 3 (2026-08-16);
post-QA bug-fix pass (2026-08-17). SOS flow: Frontend Milestone 4 (2026-08-17). MS3/MS4
product-corrections pass (2026-08-17, see below): address-source selection, the full 7-field
service address, and booking-draft persistence. Active Booking Floating Indicator feature
(2026-08-17, see below): ETA countdown on the tracking screen, post-completion review flow
(`CompletionReviewPage`). Frontend Milestone 6 (2026-08-18, see below): professional-side
"mark on the way" / "mark completed" job-status progression actions on `OrderTrackingPage`.

**Frontend Milestone 6 (2026-08-18) — professional job-status progression actions:**
- Two new professional-only actions on the shared `OrderTrackingPage.tsx`: "mark on the
  way" (`יציאה לדרך`) while `orderStatus === 'CONFIRMED'`, and "mark completed"
  (`סיום העבודה`) while `orderStatus === 'ON_THE_WAY'` — `canMarkOnTheWay`/`canComplete`
  derived booleans, gated on `user?.role === 'PROFESSIONAL'` plus the matching status,
  mirroring the existing `canCancel` pattern. Both fire immediately on click (no
  confirmation dialog, same UX as the existing cancel button and
  `IncomingRequestCard`'s accept/reject), render `fullWidth`/`primary`-variant, and are
  rendered in the same conditional slot as the customer-only cancel button — `canCancel`
  (CUSTOMER-gated, unchanged), `canMarkOnTheWay`, and `canComplete` are mutually exclusive
  by construction (an order is never simultaneously in two of `CONFIRMED`/`ON_THE_WAY`,
  and cancel is customer-only while these two are professional-only), so at most one of
  the three buttons ever renders.
- `shared/api/bookings.ts` gained `markOnTheWay(orderId)` (`POST
  /api/bookings/orders/{orderId}/on-the-way`) and `completeOrder(orderId)` (`POST
  .../complete`), both `Promise<OrderResponse>`, both PROFESSIONAL-only per backend
  enforcement — named `<verb>Order`-style like `acceptOrder`/`rejectOrder`/`cancelOrder`,
  not the bare endpoint-segment names, for naming consistency with the file's existing
  functions. Re-exported from `shared/api/index.ts` alongside the other order-action
  functions. No backend changes — both endpoints already existed (verified directly
  against `BookingsController.java`/`BookingsService.java` source) and already matched
  `docs/architecture/api-contract-bookings.md` §2.16/§2.17.
- `CANCEL_ERROR_MESSAGES` was renamed to `ORDER_ACTION_ERROR_MESSAGES` and extended with
  two new entries for the backend's 409 codes on these transitions:
  `ORDER_NOT_CONFIRMED` ("mark on the way" attempted on a non-`CONFIRMED` order) and
  `ORDER_NOT_ON_THE_WAY` ("mark completed" attempted on a non-`ON_THE_WAY` order) — a pure
  rename + extension, no behavior change to the existing cancel error handling. New
  `isUpdatingStatus`/`statusActionError` state, kept separate from
  `isCancelling`/`cancelError` (customer-only, unchanged), with `handleMarkOnTheWay`/
  `handleComplete` handlers mirroring `handleCancel`'s exact shape (set loading, await,
  `refetch()` on success, map known error codes via `ORDER_ACTION_ERROR_MESSAGES` with a
  generic fallback, clear loading in `finally`). One shared loading/error state pair
  suffices for both new handlers since `canMarkOnTheWay`/`canComplete` are mutually
  exclusive — only one is ever reachable for a given order+role at a time.
- `MyJobsPage.tsx` (`features/dashboard`) got a doc-comment-only fix: removed the stale
  "read-only by design, no on-the-way/complete actions" claim, since that's no longer true
  — the actions exist now, they just live on `OrderTrackingPage`, not on `MyJobsPage`'s own
  list. No behavioral change to that component. `useOrderStatus.ts`'s polling needed no
  change — `TERMINAL_STATUSES` already excludes `CONFIRMED`/`ON_THE_WAY`, so polling
  already continues correctly through both new transitions.
- Full design rationale (button copy choices, confirmation-dialog decision, layout/slot
  reasoning, error-message wording): `docs/architecture/professional-status-progression-actions.md`.
- **QA**: passed. Two levels of verification, kept distinct rather than blurred together:
  - **Live API-level verification**: the real backend was run against a real Postgres DB
    and QA drove the exact HTTP calls the two new buttons make, through a full two-user
    (customer + professional) order lifecycle: register → verify → login → create
    issue/slot/order → accept → on-the-way → complete. Confirmed real, non-mock
    `expectedArrivalAt` persistence, correct 409s on repeat/out-of-order calls
    (`ORDER_NOT_CONFIRMED`, `ORDER_NOT_ON_THE_WAY`), 403 when a customer attempts either
    endpoint, and that `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` notifications appear correctly
    for the customer via `GET /api/notifications`.
  - **Code-review-level verification** (not a literal browser click-through — no browser
    automation tool was available in the QA environment, consistent with every prior
    frontend milestone's QA method): confirmed by reading the component/hook code that
    `OrderTrackingPage`'s buttons call the right functions with no extra transformation,
    that `useOrderStatus`'s polling correctly continues through the non-terminal
    `CONFIRMED`/`ON_THE_WAY` states, that `useEtaCountdown`/`ActiveOrderIndicator`
    correctly consume the real `expectedArrivalAt` value that was live-verified above, and
    that the notification label map already had correct Hebrew text for both message
    types.
  - Build (`tsc -b && vite build`) and lint (`oxlint`) both passed clean. No regressions
    found in `MyJobsPage.tsx` (comment-only diff) or the existing customer-only cancel
    button.

**MS3/MS4 product-corrections pass (2026-08-17):**
- **`AddressSelectionStep`** (new, `AddressSelectionStep.tsx`/`.module.css`) replaces the bare
  `AddressFormFields` that both flows' `'address'` step previously rendered directly. A
  two-option chooser: "כתובת ברירת המחדל שלי" (the customer's saved default address, from
  `useAuth().user.defaultAddress`) vs. "כתובת אחרת לפעם הזו" (a one-off custom address, the
  unmodified `AddressFormFields`). The default option is only offered when
  `user.defaultAddress` is non-null (pre-`V20` accounts, or customers who never had one, see
  only the custom option — no dead radio pointing at nothing); when selected, the saved
  address renders **read-only** (all 7 fields shown as text) for confirmation, never
  editable, and never triggers any call that would mutate `users.default_*` (no such
  "update default address" endpoint exists). Used by both `BookingFlowPage` and
  `SosBookingFlowPage`'s `'address'` step.
- **Full 7-field address now forwarded to order creation.** Previously only
  `serviceCity`/`serviceStreet`/`serviceHouseNumber`/`serviceApartment` were sent to
  `createOrder`/`createSosOrder` (the two lines below, superseded); `serviceFloor`/
  `serviceEntrance`/`serviceAddressNotes` (already collected by `AddressFormFields`, per
  its own doc comment, but previously dropped) are now forwarded too, matching the backend's
  `V22`-extended `orders.service_*` snapshot. `BookingSummary.tsx`/`SosBookingSummary.tsx`'s
  `handleConfirm()` both send all 7 fields. `OrderTrackingPage.tsx`'s address row now also
  appends floor/entrance/`serviceAddressNotes` (when present) — professionals need this to
  actually locate/access the job, not just customers.
- **Booking-draft persistence.** `BookingFlowPage`/`SosBookingFlowPage` now read/write an
  in-progress booking draft via `useBookingDraft()` (`shared/hooks`, see that package's
  README) — hydrating on mount when the draft's `issueId`/`urgencyType` match this route,
  writing through `updateDraft(...)` on every step transition (forward **and** backward,
  since the draft tracks "where the customer currently is," not a high-water mark), and
  calling `clearDraft()` only on order-creation success (`handleConfirmed`, the sole
  clear-trigger for each flow page). On resume past the address step, the professional
  listing/slots are cheaply re-fetched from the persisted `address`/`sort`/`professionalId`/
  `slotId` rather than re-asking the customer for anything already chosen; if a
  previously-selected professional or slot is no longer valid, the resume logic falls back
  one step (`'professionals'` or `'slot'`) via the same `onSlotUnavailable`/professional-
  unavailable fallback the live flow already uses — never a hard error, never a silently
  broken confirm screen.

**Frontend Milestone 4 (2026-08-17) — SOS flow:**
- `SosBookingFlowPage` (`/issues/:issueId/sos-booking`, `CUSTOMER`-only) mirrors
  `BookingFlowPage`'s step-machine pattern but with 3 steps instead of 4 — SOS has no
  slot-picking step, the order's `bookedStart` is set to `now()` server-side and
  `bookedEnd` stays `null`: service address (`AddressFormFields`, reused unmodified) →
  professional list (`features/professionals`'s `ProfessionalList`, unmodified, calling
  `GET /api/bookings/sos-professionals` instead of `.../professionals`) → confirmation
  (`SosBookingSummary`, `POST /api/bookings/sos-orders`) → a calm success state. Shows an
  "SOS פעיל" banner (DESIGN_SYSTEM.md §49 exact copy) on every step but the success screen.
  Maps `409 ISSUE_URGENCY_MISMATCH` (reachable via a manually-edited URL to this route for
  a non-SOS issue) and `409 ISSUE_NOT_BOOKABLE` to honest Hebrew messages on the listing
  call, same pattern as `BookingFlowPage`.
- `SosBookingSummary` owns the `POST /api/bookings/sos-orders` call and its own
  double-submission guard. No date/time row (no scheduled slot). Shows a price breakdown
  (base price + flat SOS surcharge = estimated total) per DESIGN_SYSTEM.md §49's
  fee-disclosure requirement — the surcharge is a frontend constant
  (`SOS_SURCHARGE_AMOUNT = 50`, flagged in-code as a placeholder that must be kept in sync
  with the backend's `BookingsService.SOS_SURCHARGE_AMOUNT`, since no endpoint exposes that
  value ahead of order creation). The real `finalPrice`/`sosSurcharge` from `OrderResponse`
  is what's shown after creation (success screen / `/orders/:id`, unchanged). Maps `409
  SOS_PROFESSIONAL_UNAVAILABLE` (the professional toggled off between listing and this
  call) — bounces the customer back to a re-fetched professional list, mirroring
  `BookingSummary`'s `SLOT_UNAVAILABLE` handling — and `409 ISSUE_NOT_BOOKABLE`, to
  specific Hebrew messages.
- `features/issues/IssueSuccessStep.tsx`'s SOS branch now routes into
  `/issues/${issueId}/sos-booking` (previously a stub with "not available yet" copy).

**Post-QA fixes (2026-08-17):**
- `OrderTrackingPage`'s back button now checks the caller's role (`useAuth`) and
  navigates to `/orders` for a `CUSTOMER` or `/pro` for a `PROFESSIONAL`, instead of
  always going to the `CUSTOMER`-only `/orders` (which silently redirected a professional
  to `/`).
- `BookingFlowPage`'s `LISTING_ERROR_MESSAGES` now also maps `409 ISSUE_URGENCY_MISMATCH`
  (reachable only via a manually-edited URL to `/issues/:issueId/booking` for an SOS
  issue) to an honest Hebrew message instead of falling back to the generic error banner.

- `BookingFlowPage` (`/issues/:issueId/booking`, `CUSTOMER`-only) is the step machine:
  service address (`AddressSelectionStep`, see "MS3/MS4 product-corrections pass" above) →
  professional list (`features/professionals`'s `ProfessionalList`, `GET
  /api/bookings/professionals`) → slot picker (`SlotPicker`, `GET
  /api/bookings/professionals/{id}/slots`) → confirmation (`BookingSummary`, `POST
  /api/bookings/orders`) → a calm success state. Mirrors
  `features/issues/NewIssuePage.tsx`'s step-union/back-navigation pattern. The address is
  collected once and threaded through: `city`/`street`/`houseNumber`/`apartment` as query
  params on the listing call (the backend's `matching.ServiceLocation` only reads `city` for
  ETA computation, per `api-contract-professionals-reviews.md` §6.2), and all 7 fields
  (`serviceCity`/`serviceStreet`/`serviceHouseNumber`/`serviceApartment`/`serviceFloor`/
  `serviceEntrance`/`serviceAddressNotes`) on order creation.
- `SlotPicker` groups the flat `AvailabilitySlotItem[]` response by calendar day
  client-side (the API has no grouping of its own) into a date-chip row + time-chip grid,
  per DESIGN_SYSTEM.md §46-47. Every slot the API returns is already future/available, so
  nothing is ever rendered as a disabled/unavailable chip.
- `BookingSummary` owns the actual `POST /api/bookings/orders` call (mirrors `ReviewStep`'s
  self-contained pattern) and its own double-submission guard (button `loading` state).
  Maps `SLOT_UNAVAILABLE` (bounces the customer back to a re-fetched slot picker) and
  `ISSUE_NOT_BOOKABLE` to specific Hebrew messages; anything else falls back to the generic
  error copy.
- `OrderTrackingPage` (`/orders/:orderId`, either role) uses the new `useOrderStatus` hook
  (`shared/hooks`, short-polling per `overview.md` §3.3, stops once the order reaches a
  terminal status) + the new shared `StatusBadge`. Shows professional name, status,
  date/time, address, price. A customer-only cancel action appears while the order is
  `PENDING`/`CONFIRMED`/`ON_THE_WAY` (the exact actor/state matrix from
  `api-contract-bookings.md` §2.7) — no cancel action is built for the professional side
  this pass (not in this milestone's brief). **Superseded, Active Booking Floating Indicator
  feature (2026-08-17)**: the "no ETA/GPS field is shown here" claim immediately above no
  longer holds for ETA specifically — see that section below; GPS remains out of scope,
  untouched.
- `MyOrdersPage` (`/orders`, `CUSTOMER`-only per the route map) lists the caller's own
  orders (`GET /api/bookings/orders/me`), each row linking to `/orders/:id`. Empty state
  per DESIGN_SYSTEM.md §60, CTA back to `/issues/new`.

**Active Booking Floating Indicator feature (2026-08-17):**
- `OrderTrackingPage.tsx` edited: (a) renders a live ETA countdown
  (`useEtaCountdown(order.expectedArrivalAt)`, `shared/hooks`) while
  `order.orderStatus === 'ON_THE_WAY'` — `remainingMinutes`/`isArriving` displayed as "מגיע/ה
  עכשיו" or "כ־N דקות"; (b) renders a "השאירו ביקורת" (leave a review) link to
  `/orders/{id}/review` while `order.orderStatus === 'COMPLETED'` (`canReview =
  user?.role === 'CUSTOMER' && order?.orderStatus === 'COMPLETED'`) — this closes a
  reachability gap the floating indicator alone creates: the indicator shows at most one
  order at a time, so a second, lower-priority unacknowledged-`COMPLETED` order needs its
  own path to the review screen, and every order is already reachable via `MyOrdersPage` →
  `/orders/:id`, so this adds no new screen and no new API call; (c) the file's own header
  comment was updated in place — `expectedArrivalAt` is now a real, persisted field on
  `OrderDetailResponse` (supersedes the prior "no ETA field here... not fabricated" comment,
  see `docs/architecture/active-booking-floating-indicator.md` §0.1 for the architectural
  override this reflects).
- `CompletionReviewPage.tsx` + `.module.css` — new, route `/orders/:orderId/review`
  (`CUSTOMER`-only, nested under the existing `RequireAuth role="CUSTOMER"` route group,
  since `POST /api/reviews` is `CUSTOMER`-only server-side and there is no professional-side
  use for this screen). One-shot `getOrder(orderId)` fetch on mount (no polling — the order
  is already terminal by the time this screen is reachable); re-verifies
  `orderStatus === 'COMPLETED'` itself before rendering the form, since it's reachable both
  via the floating indicator (only when it's the currently-selected order) and via direct
  navigation (the `OrderTrackingPage` link above, or a stale/bookmarked URL) — never trusts
  the caller. A local 5-star rating input (`lucide-react` `Star`, same icon
  `ProfessionalCard.tsx` already uses for rating display — kept local to this file, not
  promoted to `shared/components`, since there's exactly one consumer today) plus an
  optional comment (`shared/components`'s `Textarea`). Calls `useActiveOrder().acknowledgeOrder(orderId)`
  twice, independently: once in a `useEffect` the moment the fetch confirms `COMPLETED`
  (merely viewing the screen counts as acknowledging — a review is **not** mandatory to
  consider the booking complete), and again after a successful `createReview` call
  (idempotent, kept as its own explicit call so acknowledge-on-submit still works even if a
  future revision changes the mount-trigger). Handles `409 REVIEW_ALREADY_EXISTS`
  gracefully (shows an "already reviewed" state) and `409 REVIEW_ORDER_NOT_COMPLETED`
  defensively (stale/direct-navigation edge case), same known-error-code-map pattern
  `OrderTrackingPage.tsx`'s `CANCEL_ERROR_MESSAGES` already uses.
- `index.ts` exports `CompletionReviewPage`; `router.tsx` (`app/`) adds the new route.

QA-passed (12/12 checklist items, zero bugs found). Full design record:
`docs/architecture/active-booking-floating-indicator.md`, particularly §7-§9 (new/changed
files and the deliberate `OrderTrackingPage` review-link addition beyond the literal ask).

Not built here: slot edit/delete UI, favorites toggle interaction, review
**editing/deletion** UI (`PUT`/`DELETE /api/reviews/{reviewId}` exist backend-side but
have no frontend caller yet — only creation, via `CompletionReviewPage`, is built), and
professional-side cancellation (Frontend Milestone 6 did not extend `canCancel` to the
PROFESSIONAL role — out of that milestone's scope, an explicit decision, not an oversight;
see the Frontend Milestone 6 section above). Job-status action buttons beyond cancel
(on-the-way/complete) **are now built**, as of Frontend Milestone 6 — see above.
