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

## Status
**Standard and SOS flows implemented.** Standard flow: Frontend Milestone 3 (2026-08-16);
post-QA bug-fix pass (2026-08-17). SOS flow: Frontend Milestone 4 (2026-08-17). MS3/MS4
product-corrections pass (2026-08-17, see below): address-source selection, the full 7-field
service address, and booking-draft persistence. Active Booking Floating Indicator feature
(2026-08-17, see below): ETA countdown on the tracking screen, post-completion review flow
(`CompletionReviewPage`).

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

Not built here: job-status action buttons beyond cancel (on-the-way/complete are
professional-only and belong to a future professional job-status screen, not
`features/dashboard`'s scope either), slot edit/delete UI, favorites toggle interaction,
review **editing/deletion** UI (`PUT`/`DELETE /api/reviews/{reviewId}` exist backend-side but
have no frontend caller yet — only creation, via `CompletionReviewPage`, is built).
