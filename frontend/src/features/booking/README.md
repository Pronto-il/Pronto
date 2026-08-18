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
Professional weekly availability calendar feature, M5 (2026-08-18, see below):
`OrderTrackingPage.tsx` extended for the calendar's booked-block click-through — issue
enrichment, the counterparty-name bug fix, `order.id`/`bookedEnd` rendering, the professional
customer-phone display, and week-context-preserving back-navigation. **Professional weekly
availability calendar feature, M6 (2026-08-18, final implementation milestone, see below)**:
the customer-facing Standard booking flow's start-time-picking step reworked to consume
derived `AVAILABLE` windows instead of the retired `availability_slots` rows.

**Professional weekly availability calendar — M6 (2026-08-18) — booking-flow rework:**

Full design record: `docs/architecture/professional-weekly-calendar-design.md` §9.2.3/§7.6/§10
(M6). Frontend-only; no backend change (M2 already shipped the backend side this milestone
consumes). Files:

- **`SlotPicker.tsx`/`.module.css` renamed to `StartTimePicker.tsx`/`.module.css`.** The
  date-chip-row + time-chip-grid UI is byte-for-byte unchanged — only the source of the chips
  changed. `StartTimePickerProps` replaces `slots: AvailabilitySlotItem[]`/
  `selectedSlotId: number | null` with `windows: AvailableWindow[]`/
  `defaultDurationMinutes: number`/`selectedStart: string | null`; `onSelect` now yields a
  chosen ISO `bookedStart` string instead of the whole slot object. Internally, the component
  now runs `deriveStartTimeCandidates(windows, defaultDurationMinutes)`
  (`shared/utils/availability.ts`, new) and feeds the flattened candidate-string list into the
  same unmodified `dateKey`-based day-grouping logic the original `SlotPicker` already had —
  confirms the design's own claim that this is "a small, well-contained change, not a
  different interaction paradigm."
- **`shared/utils/availability.ts`** (new) — `deriveStartTimeCandidates(windows,
  defaultDurationMinutes, gridMinutes = 30): string[]`, a pure function enumerating every
  `gridMinutes`-aligned instant from each window's own `startAt` up to and including
  `endAt - defaultDurationMinutes`. No frontend unit-test runner exists in this codebase
  (checked, none introduced just for this) — correctness was instead verified by reproducing
  the function's exact output in a standalone Node script against real API response data from
  a running backend (25 candidates across 3 windows, hand-verified against each window's own
  bounds — see the live-verification note below) and by code review of the implementation
  against the design's own spec text.
- **`shared/api/bookings.ts`**: `getProfessionalSlots`/`AvailabilitySlotItem`/
  `ProfessionalSlotsResponse` (the retired `GET .../slots?issueId=` client) replaced by
  `getAvailableWindows`/`AvailableWindow`/`AvailableWindowsResponse`, calling `GET
  .../professionals/{id}/available-windows?issueId=`. `CreateOrderRequest.slotId` dropped;
  `CreateOrderRequest.bookedStart: string` added (required). See `shared/api/README.md` for
  the full type-level detail.
- **`BookingFlowPage.tsx`**: the `'slot'`/`'confirm'` steps' internal state renamed
  (`slots`/`selectedSlot` → `windows`/`selectedStart`, plus new `defaultDurationMinutes`
  state sourced from the API response, never hardcoded); `fetchSlots` → `fetchWindows`
  (calls `getAvailableWindows`); `handleSlotUnavailable` → `handleTimeUnavailable` (same
  fallback behavior: bounce back to the picker step, re-fetch). `Step['confirm']` now carries
  `bookedStart: string` instead of `slot: AvailabilitySlotItem`. No other step (address entry,
  professional list, success screen) changed.
- **`BookingSummary.tsx`**: `slot: AvailabilitySlotItem` prop replaced by `bookedStart:
  string` + `defaultDurationMinutes: number`; a local `bookedEnd = bookedStart +
  defaultDurationMinutes` is computed **for display only** (the confirmation card's
  "09:00–10:00"-style date/time row) — never sent to the server, never trusted as
  authoritative (the server independently recomputes and validates the real `bookedEnd`).
  `onSlotUnavailable` prop renamed `onTimeUnavailable`. `ORDER_ERROR_MESSAGES`'
  `SLOT_UNAVAILABLE` entry (no longer ever returned by `createOrder`'s new validation path)
  replaced by `BOOKING_TIME_UNAVAILABLE` → "הזמן הזה כבר לא פנוי. אפשר לבחור זמן אחר." — the
  same known-error-code-to-Hebrew-message map pattern this file already used, extended with
  the new code rather than inventing a new mechanism; `ISSUE_NOT_BOOKABLE`'s existing mapping
  is unchanged.
- **`shared/hooks/bookingDraftContext.ts`/`BookingDraftProvider.tsx`**: `BookingDraft.slotId`
  replaced by `BookingDraft.bookedStart?: string`; draft schema `version` bumped `1 → 2` (an
  in-progress `version: 1` draft found in `localStorage` is discarded on load, not migrated —
  no `slotId`-to-`bookedStart` translation is possible, consistent with this file's own
  documented "unreadable/mismatched-version draft is discarded" convention). Resume-hydration
  in `BookingFlowPage.tsx` checks `deriveStartTimeCandidates(...).includes(draft.bookedStart)`
  before resuming straight to the confirm step — the direct analogue of the old
  `slots.find(item => item.slotId === draft.slotId)` check.

**Live API-contract verification** (this environment has no browser-automation tool, same
caveat every prior milestone in this project notes): built the backend jar, ran it against a
throwaway Postgres container on an alternate port (`5555`, working around this session's known
native-Windows-Postgres port-5432 shadowing issue — the same workaround prior milestones in
this session used), and drove the real HTTP calls this feature's code makes:
1. Registered a professional + customer, set the professional's working hours to `06:00–22:00`
   every day, created a manual block (`10:00–11:00` local time, tomorrow) and one existing
   `PENDING` order (`14:00–15:00` local time, tomorrow, via this same new `bookedStart`-based
   `POST /api/bookings/orders` path).
2. `GET .../available-windows?issueId=` for tomorrow correctly returned exactly three windows
   — `06:00–10:00`, `11:00–14:00`, `15:00–22:00` local time — confirming the block and the
   booking are both excluded and the 60-minute-minimum filter holds (every returned window is
   `>= 60` minutes).
3. Reproduced `deriveStartTimeCandidates`'s exact algorithm in a standalone Node script against
   that real response: **25 candidates**, hand-verified against each window's own bounds
   (7 + 5 + 13, matching `(windowMinutes − 60) / 30 + 1` per window) — confirms the utility
   function's real behavior matches its spec.
4. Submitted `POST /api/bookings/orders` with `bookedStart` = one derived candidate
   (`08:30Z`/`11:30` local) → `201`, server-computed `bookedEnd` = exactly
   `bookedStart + 60 min` (`09:30Z`) — confirms the client's display-only `bookedEnd`
   computation in `BookingSummary.tsx` matches the server's authoritative one.
5. Submitted a second `POST /api/bookings/orders` with a `bookedStart` deliberately inside the
   already-booked `11:00–12:00Z` window → **`409 BOOKING_TIME_UNAVAILABLE`**, confirming the
   exact error code `BookingSummary.tsx`'s new `ORDER_ERROR_MESSAGES` mapping keys off.
6. Regression-checked the old retired route (`GET .../professionals/{id}/slots?issueId=`) —
   confirmed it no longer functions (backend M2 already removed it); confirmed
   `GET /api/bookings/orders/{id}` still returns `customerPhone` correctly for a `PENDING`
   order (unrelated M5 field, sanity-checked as a no-regression spot check); confirmed the SOS
   path (`PUT .../sos-availability`, `POST /api/issues` with `urgencyType: "SOS"`,
   `POST /api/bookings/sos-orders`) is completely unaffected — `201`, `bookedEnd: null`, flat
   surcharge applied, exactly as before this milestone.
7. `tsc -b && vite build` and `oxlint` both clean (no new warnings/errors introduced; the two
   pre-existing `ProfessionalList.tsx` fast-refresh warnings and one `qa-tmp-ms9/` script
   warning are unrelated to this milestone's files).

**Not independently verified in-browser** (no browser-automation tool available in this
environment, consistent with every prior frontend milestone in this project) — left for
`pronto-qa`: the actual rendered date-chip-row/time-chip-grid UI, the "09:00–10:00"-style
confirmation-card text rendering, and the booking-draft resume-from-`localStorage` flow
end-to-end in a real browser session (the underlying logic was verified by direct API-contract
testing plus code review, not a live click-through).

**Post-QA bug-fix pass (2026-08-18) — conflicting-booking error banner never rendered:**
`pronto-qa` found, live, via a genuine server-side race (two customers booking the same
window), that `BookingSummary.tsx`'s `handleConfirm` catch block called
`setBannerError(...)` and `onTimeUnavailable()` on the same tick, but `onTimeUnavailable`
(`BookingFlowPage.tsx`'s `handleTimeUnavailable`) immediately calls `setStep({ name: 'slot',
... })`, unmounting `BookingSummary` before its just-set banner state ever painted — the
customer was silently bounced back to the `slot` step with no visible explanation. Fixed by
moving the error message up to `BookingFlowPage`, the level that survives the step
transition: `onTimeUnavailable` now takes the message as a parameter
(`(message: string) => void`, was `() => void`) instead of `BookingSummary` guessing at a
banner it can't render; `BookingSummary` no longer calls `setBannerError` for
`BOOKING_TIME_UNAVAILABLE` specifically (its other error codes, e.g. `ISSUE_NOT_BOOKABLE`,
are unaffected — those stay on the `confirm` step, so the existing local `bannerError` state
still works for them). `BookingFlowPage` stores the message in a new `timeUnavailableError`
state (deliberately not reusing `slotsError` — that's a `getAvailableWindows` fetch-failure
banner, and `fetchWindows`'s own `setSlotsError(null)` runs synchronously in the same tick as
`handleTimeUnavailable`, which would otherwise clobber the message before paint) and renders
it via the same `role="alert"` banner convention already used elsewhere in this file
(`professionalsError`/`slotsError`) on the `slot` step, above `StartTimePicker`. Cleared on
any voluntary path away from the stale conflict context: selecting a new professional,
continuing past the slot step, going back, or picking a new start time. Live-verified via a
fresh Playwright script driving a real two-customer race against a running backend
(`frontend/qa-tmp-calendar/pw-bugfix1-verify.mjs`): customer A reaches the confirm step,
customer B books the exact same professional+`bookedStart` first via a direct API call,
customer A submits and gets bounced back to the `slot` step with the exact Hebrew banner
text ("הזמן הזה כבר לא פנוי. אפשר לבחור זמן אחר.") visible as a real `role="alert"` element,
then successfully completes the booking after picking a different time (7/7 assertions
passed, screenshots in the same directory). `tsc -b`, `vite build`, and `oxlint` all clean.

**Professional weekly availability calendar — M5 (2026-08-18) — `OrderTrackingPage.tsx` extension:**

Full design record: `docs/architecture/professional-weekly-calendar-design.md` §7.5/§9.1/§10
(M5). This screen is the click-through destination for a `BOOKED` segment on
`features/dashboard/WeeklyCalendarGrid.tsx`'s calendar — see that package's own M5 README
section for the click-routing side. Five purely additive changes, all to the existing
`OrderTrackingPage.tsx`/`.module.css`; no new component, no new route:

1. **Issue enrichment.** A one-shot `getIssue(order.issueId)` fetch (`shared/api/issues.ts`,
   already existed, no backend change) runs once `order` resolves — keyed on `issueId` alone
   (not the whole `order` object, which gets a new identity on every status-poll tick) so it
   never re-fetches an issue that hasn't changed. Renders category (`getCategoryNameHe`,
   reused from `shared/api/categories.ts`), description, an `SOS` tag (reusing
   `IncomingRequestCard.tsx`'s exact `sosTag` styling/copy), and issue photos (the same
   presigned-URL `<img src={image.imageUrl}>` pattern `IncomingRequestCard.tsx` already
   established) — placed between the status card's ETA/divider section and the existing
   date/address rows. A failed issue fetch is swallowed silently (best-effort enrichment —
   the rest of the tracking screen, including all actions, still works from `order` alone).
2. **`order.id`/`order.bookedEnd` now rendered** — both were already present on
   `OrderDetailResponse`, simply not displayed before. `id` renders as "הזמנה #N" under the
   counterparty name; `bookedEnd` appends to the existing date/time row as an end-time range
   (`14:00–15:00`) when non-null (always non-null for a Standard order as of the M2
   order-creation rework; still correctly `null`/omitted for an SOS order).
3. **Counterparty-name bug fixed.** Previously always rendered `order.professionalName`
   regardless of viewer role — wrong for a professional viewing their own job. Now:
   `user.role === 'PROFESSIONAL'` → `order.customerName`; `user.role === 'CUSTOMER'` →
   `order.professionalName`.
4. **Customer phone for a professional viewer.** `OrderDetailResponse` gained a
   `customerPhone: string | null` field (`shared/api/bookings.ts`) mirroring the real
   backend DTO (design §9.1). Rendered in a new row ("טלפון הלקוח") only when
   `user.role === 'PROFESSIONAL'` — no extra status gating needed beyond the role check,
   since the backend already scopes this field to a party of the order from `PENDING`
   onward (live-verified, see `features/dashboard/README.md`'s M5 verification section).
   Never rendered for a `CUSTOMER` viewer — no reciprocal requirement exists in any source
   document.
5. **Week-context-preserving back-navigation (design §43).** A new `TrackingLocationState`
   shape (`{ returnTo?: { weekStart: string } }`) is read via `useLocation().state`. When
   `WeeklyCalendarGrid` navigated here from a `BOOKED` segment click, it always carries this
   state; the back button then goes to `/pro/availability?week=${returnTo.weekStart}`
   instead of this screen's normal role-based default (`/pro` for a professional, `/orders`
   for a customer) — every other entry point into this screen (the incoming-requests feed,
   `MyJobsPage`, `MyOrdersPage`, the floating active-order indicator) passes no such state,
   so its own existing back-navigation behavior is completely unaffected.

**Verification**: see `features/dashboard/README.md`'s M5 section for the shared live
API-contract testing (both packages were verified together against the same running
backend/order/issue, since this screen's new fields are populated by that same session's
data). `tsc -b`/`vite build`/`oxlint` all clean on this file specifically. §43's round-trip
and the full §16 booking-summary content checklist (order id, status, category, description,
urgency tag, booked start/end, ETA when present, customer/professional name per role, full
address snapshot, customer phone for a professional viewer, issue images, existing actions)
were verified by code review plus the live API-contract data above — no browser-automation
tool was available in this environment to click through the actual rendered page, consistent
with every prior frontend milestone in this project.

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
  nothing is ever rendered as a disabled/unavailable chip. **Superseded, professional weekly
  availability calendar feature M6 (2026-08-18)**: this component is renamed
  `StartTimePicker` and its chip source is now derived start-time candidates, not
  `availability_slots` rows — see the M6 section near the top of this file for the current
  shape; the grouping UI itself is unchanged.
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
