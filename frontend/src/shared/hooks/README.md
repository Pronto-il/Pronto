# shared/hooks

## Purpose
Reusable React hooks shared across features.

## Responsibilities
- Auth context/hook (current user, token, login/logout).
- Short-polling status hook (per `docs/architecture/overview.md` §3.3) used by the
  booking tracking screen and the professional's incoming-request feed — shipped in
  Frontend Milestone 3 (2026-08-16).
- The app-wide polling scheduler (`pollingStore.ts`) every one of those hooks runs on: one
  timer per resource rather than per consumer, visibility-aware, deduplicating — added in the
  request-efficiency pass (2026-08-24), see the section at the end of this file.
- Booking-draft persistence context/hook (issue-creation + booking-flow in-progress state,
  survives reload via `localStorage`) — shipped in the MS3/MS4 product-corrections pass
  (2026-08-17), see below.
- Active-booking floating-indicator context/hooks (cross-order priority selection, ETA
  countdown, review-acknowledgement persistence) — shipped in the Active Booking Floating
  Indicator feature (2026-08-17), see below.
- Notification-bell polling hook (`useNotifications`) — shipped in Frontend Milestone 5
  (2026-08-18), see below.
- Toast provider/hook triad (`ToastProvider`/`useToast`) — shipped in MS1 (Visual
  Foundation & Motion System, 2026-08-20), mounted inert (no call sites yet) for MS2+ to
  use, see below.

## Structure
- `authContext.ts` — the `AuthContext` (React context) + `AuthContextValue` type. Kept
  separate from the provider component so the file only exports non-component values
  (avoids the React Fast Refresh / `only-export-components` lint warning).
- `AuthProvider.tsx` — the context provider. Holds `token`/`user`/`isLoading`; persists
  the token to `localStorage` (`pronto_auth_token`); rehydrates on app load by calling
  `GET /api/users/me` (a 401 during rehydration just clears the token — no forced
  redirect from here). Registers the token-getter `shared/api/httpClient.ts` uses to
  attach the `Authorization` header. `login(email, password)` calls
  `POST /api/auth/login`, stores the token, then fetches the full `GET /api/users/me`
  profile so the context always holds one consistent `UserMeResponse` shape.
  `logout()` is a client-side-only discard (no server-side logout endpoint in v1.0).
- `useAuth.ts` — the `useAuth()` hook, throws if used outside `AuthProvider`.
- `pollingStore.ts` — **new, request-efficiency pass (2026-08-24).** The module-scoped
  scheduler every polling hook now runs on; `usePolling` is its React binding. It owns one
  timer and one response *per resource key* rather than per consumer, suspends polling while
  `document.visibilityState !== 'visible'` (one listener for the whole app, opt out with
  `pollWhenHidden`), collapses concurrent identical reads onto one in-flight request, re-times
  rather than re-fires when a cadence changes, and keeps an entry for 30s after its last
  subscriber leaves so a remount reuses the response. Also exposes `primeResource(key, data)`
  (publish a mutation's own response as the resource's state, instead of spending a `GET`
  re-reading what a `PUT` just answered) and `clearPollingStore()` — which `AuthProvider` calls
  on logout and on the 401 session-end path, because entries are keyed by request rather than
  by caller and would otherwise serve the previous account's data to the next one. Shared keys
  live in `shared/api/resourceKeys.ts`. Full rationale, cadences and measured before/after
  numbers: `docs/architecture/frontend-request-efficiency.md`.
- `usePolling.ts` — the generic short-polling hook: fetches on mount, then re-fetches every
  `intervalMs` (default 4000ms). Skips a tick if the previous request is still in flight
  (never overlaps requests), cleans up on unmount, and is a no-op while `enabled` is `false`.
  Backing implementation for `useOrderStatus` and every other polling need, per
  `docs/architecture/overview.md` §3.3 (short-polling, not WebSocket). Its signature is
  unchanged; the request-efficiency pass added four optional options — `key` (share the poll
  with every other consumer of the same key; omitted, the hook generates a per-instance key
  from `useId` and behaves exactly as an unshared interval did), `pollWhenHidden`,
  `fetchOnMountWhenDisabled` (read once on mount without ever starting an interval — the
  notification bell's bootstrap), and `maxStaleOnMountMs` (how old a cached value may be and
  still satisfy that read, so a screen borrowing another owner's warm entry cannot render an
  arbitrarily stale view of it).
  **Bug fixed in frontend redesign MS5 (2026-08-20), found live during that milestone's QA**:
  the in-flight guard also swallowed an explicit `refetch()`, not just an overlapping *tick*.
  Those are different things — a `refetch()` follows a user action that has just changed the
  data server-side (cancel an order, mark on the way, mark completed), so dropping it left the
  screen showing the state the user had just changed until the next tick, up to `intervalMs`
  later. Reproduced on `OrderTrackingPage`: confirming a cancellation kept the pre-cancel
  status on screen for ~4s. An explicit refetch is now **queued** and runs as soon as the
  in-flight request settles; overlapping polls are still skipped exactly as before. Affects
  every `refetch()` caller, i.e. all three order-status actions.
- `useOrderStatus.ts` — order-tracking-screen polling wrapper around `usePolling`, built
  on `shared/api/bookings.ts`'s `getOrder` (`GET /api/bookings/orders/{orderId}`). Stops
  polling once the last-observed `orderStatus` reaches a terminal state (`COMPLETED`/
  `CANCELLED`/`REJECTED`/`EXPIRED`) — no point short-polling a status that can never
  change again. Consumed by `features/booking/OrderTrackingPage.tsx`; the professional's
  incoming-request feed (`features/dashboard/IncomingRequestsPage.tsx`) reads
  `useLivePendingRequests()` instead (a shared list resource, not a single order — it used to
  poll that list itself, see the 2026-08-24 section at the end of this file). Paces itself by
  lifecycle as of that same pass, including a `CONFIRMED` cadence that depends on how near
  `bookedStart` is.
- `bookingDraftContext.ts` — the `BookingDraftContext` (React context) + `BookingDraft`/
  `BookingDraftStage`/`BookingDraftPhoto`/`BookingDraftContextValue` types, kept separate
  from the provider component for the same Fast-Refresh-lint reason `authContext.ts` is.
  Also exports the pure helper `resolveDraftRoute(draft)`, which maps a draft's `stage` to
  its resume route (`/issues/new` for the three issue-creation stages; `/issues/{issueId}
  /booking` or `.../sos-booking`, by `urgencyType`, for every later stage). `BookingDraft`
  holds: `version` (schema version, `2` as of the professional weekly availability calendar
  feature M6 — an unreadable/mismatched-version draft found in storage is discarded, not
  migrated), `ownerId` (the user this draft belongs to, see the leakage guard below), `stage`,
  `urgencyType`, the issue-creation fields (`description`/`photos`/`clarificationAnswers`/
  `categoryId`), `issueId` (once the issue is persisted), the address-selection fields
  (`addressMode`/`address`, the full 7-field `AddressValue`), and the professional/start-time-
  selection fields (`professionalId`/`sort`, narrowed to `'RECOMMENDED' | 'CHEAPEST'` — not
  the 3-value API `ProfessionalSort` type, since a draft only round-trips a value the UI
  itself set/could set — and `bookedStart`, STANDARD-only). **`bookedStart` — professional
  weekly availability calendar feature M6 (2026-08-18): replaces the retired `slotId: number`
  field**, since `CreateOrderRequest` (and the customer's start-time-picking step) now works
  in terms of a chosen ISO instant, not a pre-made `availability_slots` row id — see
  `features/booking/README.md`'s M6 section. `version` was bumped `1 → 2` for this shape
  change; there is no `slotId`-to-`bookedStart` migration path (a discarded/expired slot id
  carries no timestamp to translate), so a `version: 1` draft found in storage is simply
  discarded on load, same as any other unreadable/mismatched-version draft.
  **`BookingDraftPhoto` — corrected, backend MS9 (2026-08-18): dropped
  `imageUrl`, now `{ imageKey: string }` only.** Previously held both `imageKey` and a
  `imageUrl` described as "durable" — that was true only while `POST /api/storage/images`'s
  response returned a permanent, non-expiring proxy URL; it stopped being true once upload
  responses became presigned (300s TTL), since a URL saved into a paused draft would
  routinely be expired by the time the draft is resumed. **Consequence: resuming a draft
  is no longer a pure `localStorage` read with zero network cost.**
  `features/issues/NewIssuePage.tsx`'s resume flow now also fires a batch request —
  `shared/api/storage.ts`'s `getPresignedImageUrls(imageKeys)` — immediately on mount, to
  re-resolve every persisted photo's raw `imageKey` into a fresh presigned URL before it can
  be displayed (each photo shows a spinner placeholder, via `PhotoUploader`'s widened
  `previewUrl: string | null`, until its URL resolves). A partial response (some keys
  unresolvable, e.g. a corrupted/stale draft) degrades gracefully — the affected photo is
  dropped from state and the draft self-heals on the next save, with a non-blocking inline
  notice — rather than failing the whole resume. See
  `docs/architecture/backend-ms9-presigned-image-urls-design.md` §12 for the full design and
  `frontend/src/shared/components/README.md`'s corrected paragraph on `PhotoUploader`.
- `BookingDraftProvider.tsx` — the context provider. Mirrors `AuthProvider`'s shape/location
  exactly (global, cross-feature, `localStorage`-backed state, consumed by both the app
  shell and multiple `features/*` folders — deliberately placed here rather than in a new
  `shared/booking-draft/` folder, since this project already has a home for exactly this
  class of thing). Reads `localStorage['pronto_booking_draft']` on mount (mirrors
  `AuthProvider`'s `pronto_auth_token` naming). `updateDraft(patch)` is an upsert — creates
  the draft (with sensible defaults) if none exists, else shallow-merges the patch, always
  refreshing `updatedAt` and re-writing storage; called on every step transition, forward
  and backward, in `NewIssuePage`/`BookingFlowPage`/`SosBookingFlowPage`. `clearDraft()` is
  called only where a flow genuinely finished or was dismissed: a booking flow page's
  post-order-creation success handler, `features/sos`'s `ProntoSosScreen` on a successful SOS
  selection (which creates an order, so it is the same condition — added with the Pronto SOS
  customer frontend, 2026-08-21, and guarded on the draft belonging to that issue), and
  `BookingDraftIndicator`'s explicit dismiss action — never anywhere else. **Cross-account
  leakage guard**: nested inside `AuthProvider` in `App.tsx` specifically so it can call
  `useAuth()` internally; watches `user` and auto-clears the draft if `user` becomes `null`
  (logout) or `user.id !== draft.ownerId` (a different account logs in on the same browser)
  — `localStorage` isn't otherwise user-scoped, so this is necessary data hygiene, not a new
  product decision.
- `useBookingDraft.ts` — the `useBookingDraft()` hook, throws if used outside
  `BookingDraftProvider`.
- `activeOrderContext.ts` — the `ActiveOrderContext` (React context) + `ActiveOrderContextValue`/
  `ActiveOrderSelection`/`ActiveOrderIndicatorState` types, kept separate from the provider
  component for the same Fast-Refresh-lint reason `authContext.ts`/`bookingDraftContext.ts`
  are. Also exports the pure helpers `selectActiveOrder(orders, acknowledgedOrderIds)` — the
  priority-selection algorithm (§5 of the design doc below): `ON_THE_WAY` > `PENDING`/
  `CONFIRMED` > unacknowledged `COMPLETED`, with `CANCELLED`/`REJECTED`/`EXPIRED` excluded
  from the candidate set entirely and a documented, explicitly-flagged-as-a-recommendation
  tie-break within each tier (soonest-`expectedArrivalAt` for `ON_THE_WAY`, most-recently-
  created for `PENDING`/`CONFIRMED`, most-recently-`updatedAt` for unacknowledged
  `COMPLETED`) — and `resolveActiveOrderRoute(selection)`, which routes a
  `COMPLETED_UNACKNOWLEDGED` selection to `/orders/{id}/review` and every other state to
  `/orders/{id}`. No business logic lives in the indicator component itself — both helpers
  are the single source of truth.
- `ActiveOrderProvider.tsx` — the context provider. Nested inside `AuthProvider` in
  `App.tsx` (alongside `BookingDraftProvider`) so it can call `useAuth()` internally. Polls
  `GET /api/bookings/orders/me` (`getMyOrders`) via `usePolling`, enabled only for an
  authenticated `CUSTOMER` — a second, independent instance of the same `usePolling`
  primitive `useOrderStatus` already uses, at list granularity rather than per-order detail
  granularity (deliberate: the priority-selection algorithm needs to see every one of the
  customer's orders in one call, which only the list endpoint can supply; the lean
  `OrderSummaryResponse` shape already contains 100% of what the compact indicator renders —
  `orderStatus`/`expectedArrivalAt`/`id` — so no follow-up detail-poll is made). Also owns
  the acknowledged-completed-order-ids state, persisted to
  `localStorage['pronto_ack_completed_orders']` as `{ ownerId, orderIds }`, with the same
  cross-account-leakage guard `BookingDraftProvider` already established for its own key: on
  mount and whenever `useAuth().user` changes, a stored record that doesn't belong to the
  current session's user is cleared outright (logout, or a different account logging in on
  the same browser), never merged/reconciled. `acknowledgeOrder(orderId)` is idempotent (a
  no-op if already present). Exposes `{ selection, acknowledgeOrder, refetch }`.
- `useActiveOrder.ts` — the `useActiveOrder()` hook, throws if used outside
  `ActiveOrderProvider` (mirrors `useBookingDraft.ts` exactly).
- `useEtaCountdown.ts` — pure presentational hook: given `expectedArrivalAt: string | null`,
  ticks every 1 second (a `setInterval`, cleaned up on unmount/dependency change) and returns
  `{ remainingMinutes: number | null; isArriving: boolean }`. **Always recomputed from
  `Date.now()` vs. the persisted absolute `expectedArrivalAt` timestamp — never a
  locally-decremented counter.** This is what makes the countdown survive a remount or page
  refresh by construction: the source of truth is the absolute timestamp the backend
  persisted (`orders.expected_arrival_at`, see `backend/.../bookings/README.md`), not any
  client-held countdown state. Shared by `app/ActiveOrderIndicator.tsx` and
  `features/booking/OrderTrackingPage.tsx` (the only two consumers).
- `useCountdown.ts` — **new, Pronto SOS customer frontend MS1 (2026-08-21).** The same
  recompute-from-`Date.now()` principle as `useEtaCountdown.ts` above, at second resolution:
  given an absolute ISO deadline it returns `{ remainingSeconds, label: 'm:ss', isElapsed }`.
  A separate hook rather than a parameter on `useEtaCountdown` because the two report different
  units for different jobs — whole minutes is right for "arriving in ~20 min" and wrong for a
  two-minute selection window, which would read "2 דקות" for most of its life and then jump.
  **Presentation only**: the backend owns and enforces every SOS deadline, and `isElapsed` is
  never treated as a state transition. Consumer: `features/sos`'s `SosCandidateTray`.
- `useSosRequest.ts` / `useSosRealtime.ts` — **new, Pronto SOS customer frontend MS1
  (2026-08-21).** The customer's SOS state, and the socket behind it.
  `useSosRequest(sosRequestId)` returns `{ request, candidates, selectionOpen, isLoading, error,
  refetch, realtimeStatus }`, built on `usePolling` (3s while live, stopped at a terminal status —
  `useOrderStatus`'s precedent) over `GET /api/sos/requests/{id}` + `GET .../candidates`.
  **REST is the source of truth and this hook enforces that**: realtime never patches state, it
  only triggers a refetch, because the pushed payloads are deliberately minimal (ids, counts,
  deadlines) and only REST re-applies elapsed deadlines server-side. Two details worth knowing:
  candidates stop being fetched once a professional is selected (the endpoint would return an
  empty list, and the last pre-selection view is retained so the chosen professional stays on
  screen), and the `refetch` handed to realtime is a stable wrapper so an inline callback can't
  churn the socket. `useSosRealtime` is the thin binding to `shared/realtime`: it takes the JWT
  from `useAuth`, subscribes to `/user/queue/sos`, and calls `onResync` on every (re)subscribe —
  which is what covers whatever was missed while the socket was down, since nothing replays.
- `proSosContext.ts` / `ProSosProvider.tsx` / `useProSos.ts` — **new, Pronto SOS professional
  frontend MS2 (2026-08-21).** The professional's SOS state, as the same context+provider+hook
  triad — and at the same scope — as `PendingRequestsProvider`: mounted inside
  `ProDashboardLayout`, so the nav's SOS badge and the `/pro/sos` screen share one poll and one
  `/user/queue/sos` subscription instead of each opening their own. Mounting it on the layout
  rather than the route is what makes discovery work: an offer has a ~2-minute window, so a
  professional sitting on the availability calendar must learn about it without navigating — the
  provider raises the "קריאת SOS חדשה התקבלה" toast (via `useToast`) and lights the badge from
  anywhere under `/pro/*`. Deliberately not in `App.tsx`; none of it concerns a customer session.
  Exposes derived buckets (`incomingOffers`/`availableOffers`/`activeJob`/`resolvedOffers`) rather
  than a raw list, because the bucketing *is* the product semantics — an offer awaiting an answer,
  one where availability was reported and the customer is still choosing, and the job actually won
  are three different situations, and collapsing them is how "you got the job!" copy ends up in
  front of a professional who merely said they were free. Three details worth knowing: it fetches
  with `includeClosed=true` (a `NOT_SELECTED` offer leaves the live set instantly, so the default
  inbox cannot show "the customer chose someone else" at all); its fetcher also reads
  `GET /api/sos/requests/{id}` while a job is live, because the exact address exists nowhere else
  and fetching both in one tick keeps them consistent; and realtime `eventId`s are de-duplicated,
  since a message can legitimately arrive twice and a duplicate toast on an urgent inbox is real
  noise. Same contract as the customer side: realtime triggers a refetch, never a state patch.
- `useNotifications.ts` — **new, Frontend Milestone 5.** Wrapper around
  `usePolling` (`GET /api/notifications` via `shared/api/notifications.ts`'s
  `getNotifications`). **Gated on active-order state as of the request-efficiency pass
  (2026-08-24)**: nothing in this product creates a notification outside an order's lifecycle,
  so the hook polls only while `useActiveOrder().hasLiveOrder` is true (15s, or 10s with the
  panel open) and **does not poll at all otherwise** — not slower, not at all. The gate is read
  from the context the floating indicator already maintains, so no request is made in order to
  decide whether to make requests. Correct initial rendering is preserved by one read at mount
  (`fetchOnMountWhenDisabled`, a no-op if the key already holds data) plus one read each time
  the bell's panel is opened, which is a user action rather than an interval.
  `COMPLETED_UNACKNOWLEDGED` deliberately does not count as live — the work is finished and the
  review prompt is local UI state, so an undismissed prompt cannot keep a poller alive over a
  dead order. A `PROFESSIONAL`/`ADMIN` session has no active-order context and therefore never
  polls the bell; their live signals arrive on the surfaces that own them (`ProSosProvider`'s
  socket and toast, the pending-request badge). Deliberately a plain
  hook, not a React context: unlike `useBookingDraft`/`useActiveOrder` it has exactly one
  consumer (`features/notifications/NotificationBell.tsx`), no cross-page state to
  coordinate. Exposes `{ notifications, unreadCount, isLoading, markAsRead(id),
  markAllAsRead() }`. The request is `unreadOnly=true` (this paragraph previously described an
  unfiltered feed — that stopped being true when the bell became a self-cleaning inbox), so
  `unreadCount` is simply `notifications.length` rather than separate state, and an optimistic
  `markAsRead`/`markAllAsRead` is instantly reflected in the badge with nothing else to keep in
  sync. Both mutations update local state immediately and fire their `POST` in the background
  without awaiting it or forcing a `refetch()` afterwards — a failed request just self-corrects
  on the next read (no error toast, low-stakes action). A `dismissedIds` ref filters incoming
  responses so a row optimistically removed cannot flash back while its `POST` is still in
  flight.
- `toastContext.ts` / `ToastProvider.tsx` / `useToast.ts` — **new, MS1 (Visual Foundation &
  Motion System, 2026-08-20).** Same structural triad as `authContext.ts`/`AuthProvider.tsx`/
  `useAuth.ts` (context + non-component values kept in their own file for the Fast-Refresh
  lint reason noted above; provider owns state; hook throws outside the provider). Toast
  tone vocabulary is a deliberately-trimmed subset of `Badge`'s `BadgeTone`
  (`shared/components/Badge.tsx`) — `'neutral'|'success'|'error'|'info'` only; `'primary'`/
  `'warning'` are dropped since no MS1+ toast consumer needs them yet (per the MS1 plan's
  scope-discipline decision — extend from `BadgeTone` if/when a real consumer needs one, not
  speculatively). `useToast()` exposes `{ toasts, showToast, dismissToast }`:
  `showToast(message: string, options?: { tone?: ToastTone; duration?: number }): string`
  enqueues a toast (default tone `'neutral'`, default `duration` `4000`ms) and returns its
  `id`; `dismissToast(id: string): void` dismisses a toast before its auto-dismiss timer
  fires (e.g. a manual close button). The stack is capped at `MAX_TOASTS = 3` — enqueuing a
  4th dismisses the oldest immediately (oldest evicted first) rather than dropping the
  newest. `ToastProvider` is mounted near the root in `App.tsx` (see `app/README.md`),
  paired with `shared/components/ToastViewport.tsx` for the actual render (portaled,
  `role="status"`/`aria-live="polite"`, `framer-motion` enter/exit — see
  `shared/components/README.md`'s `ToastViewport` entry). **Mounted inert in MS1**: no
  `showToast()` call sites exist yet anywhere in the app (the `/__design` dev showcase route
  is the only current caller, for demonstration) — this is plumbing for MS2+ features (e.g.
  booking-flow success/error feedback) to consume.

- `pendingRequestsContext.ts` / `PendingRequestsProvider.tsx` / `usePendingRequests.ts` —
  **new, MS6 Professional Command Center (2026-08-20).** Same structural triad as
  `activeOrderContext.ts`/`ActiveOrderProvider.tsx`/`useActiveOrder.ts` (context + non-component
  values kept separate for the Fast-Refresh lint reason noted above), but deliberately scoped
  narrower: mounted inside `features/dashboard/ProDashboardLayout.tsx` (wrapping the sidebar
  nav + `<Outlet />`), not in `App.tsx`. Polls `GET /api/bookings/orders/me?status=PENDING`
  (`getMyOrders('PENDING')`) via `usePolling` at a 25s interval (matching
  `WeeklyCalendarGrid.tsx`'s own `CALENDAR_POLL_INTERVAL_MS` cadence — a count badge doesn't
  need `IncomingRequestsPage`'s own 5s live-action cadence), exposing `{ count, refetch }`. No
  selection-algorithm helpers (unlike `activeOrderContext.ts`) — this context only ever needs a
  raw pending-order count. Consumed by both `ProDashboardLayout`'s sidebar badge and
  `features/dashboard/CommandCenterBanner.tsx` — see `features/dashboard/README.md`'s MS6
  section for the full record.

## Status
`AuthProvider`/`useAuth` implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`). `usePolling`/`useOrderStatus` shipped in
**Frontend Milestone 3 — Standard booking flow (2026-08-16)**, not Milestone 5 as this
doc previously (incorrectly) said — Milestone 5's backend work (the `notifications`
package's email-dispatch and order-expiry scheduler jobs) is unrelated server-side
scheduling, already complete separately, and never blocked this hook's frontend delivery.

**MS3/MS4 product-corrections pass (2026-08-17)**: `bookingDraftContext.ts`/
`BookingDraftProvider.tsx`/`useBookingDraft.ts` are new (see "Structure" above).
`BookingDraftProvider` is wired into `App.tsx` nested inside `AuthProvider` (needed for the
cross-account leakage guard). Consumed by `features/issues/NewIssuePage.tsx`,
`features/booking/BookingFlowPage.tsx`/`SosBookingFlowPage.tsx`, and
`app/BookingDraftIndicator.tsx` (the persistent nav indicator, see `app/README.md`). Full
design record: `docs/architecture/ms3-ms4-corrections-design.md` §4.

**Active Booking Floating Indicator feature (2026-08-17)**: `activeOrderContext.ts`/
`ActiveOrderProvider.tsx`/`useActiveOrder.ts`/`useEtaCountdown.ts` are new (see "Structure"
above). `ActiveOrderProvider` is wired into `App.tsx` nested inside `AuthProvider`, alongside
`BookingDraftProvider` (needed for both its own `useAuth()` call and the cross-account
acknowledgement-state guard). Consumed by `app/ActiveOrderIndicator.tsx` (the new floating
nav-shell widget, see `app/README.md`) and `features/booking/CompletionReviewPage.tsx`/
`OrderTrackingPage.tsx` (see `features/booking/README.md`). Structurally and data-wise
independent from `BookingDraftProvider`/`useBookingDraft` — a `BookingDraft` (client-only) and
an `Order` (backend row) can never represent the same booking simultaneously, and the two
context/indicator pairs can coexist on screen for two different, unrelated bookings without
conflict. QA-passed (12/12 checklist items, zero bugs). Full design record:
`docs/architecture/active-booking-floating-indicator.md`, particularly §3 (list-poll-only
sync mechanism), §5 (priority-selection algorithm), and §6 (acknowledgement-tracking
mechanism).

**Frontend Milestone 5 — Notifications (2026-08-18)**: `useNotifications.ts` is new (see
"Structure" above), consuming the already-complete backend `notifications` package
(read-only, no backend changes). Consumed by `features/notifications/NotificationBell.tsx`
(see `features/notifications/README.md`).

**Backend MS9 — presigned image URLs (2026-08-18)**: `bookingDraftContext.ts`'s
`BookingDraftPhoto` dropped `imageUrl`, keeping only `imageKey` (see "Structure" above) —
this fixes a real, concretely-reachable bug (a paused draft's photos going blank after the
presigned-URL TTL elapsed on resume), not just a naming cleanup. Consumed by
`features/issues/NewIssuePage.tsx`'s resume flow, which now also calls the new
`getPresignedImageUrls` batch endpoint on mount. QA live-verified booking-draft resume works
correctly after this change. Full design record:
`docs/architecture/backend-ms9-presigned-image-urls-design.md` §12.

**Professional weekly availability calendar, M6 (2026-08-18, final implementation
milestone)**: `bookingDraftContext.ts`'s `BookingDraft.slotId` was replaced by
`bookedStart: string`, and the draft schema `version` was bumped `1 → 2` (see "Structure"
above) — the direct consequence of `CreateOrderRequest` retiring `slotId` in favor of a
client-chosen `bookedStart` instant. `BookingDraftProvider.tsx`'s version-check literal was
updated to match (`parsed.version !== 2`); no other change to this file or
`useBookingDraft.ts`. Resume-hydration logic itself lives in
`features/booking/BookingFlowPage.tsx`, not here — see that package's README for the M6
record and live-verification detail.

**MS1 — Visual Foundation & Motion System (2026-08-20)**: `toastContext.ts`/
`ToastProvider.tsx`/`useToast.ts` are new (see "Structure" above). `ToastProvider` is wired
into `App.tsx` near the other providers, paired with `shared/components/ToastViewport.tsx`
for rendering (see `app/README.md`'s MS1 entry for exact nesting). No other hook in this
package changed for MS1 — the milestone's `Card`/`Button`/`Modal`/etc. work lives entirely
in `shared/components/`.

**MS6 — Professional Command Center (2026-08-20)**: `pendingRequestsContext.ts`/
`PendingRequestsProvider.tsx`/`usePendingRequests.ts` are new (see "Structure" above), mounted
in `features/dashboard/ProDashboardLayout.tsx`, not `App.tsx` — deliberately scoped to the
`/pro/*` subtree only, unlike every other provider in this file. Full design record:
`docs/architecture/frontend-ms6-professional-command-center-design.md` §3.3.

**MS1 finalization — `useNotifications` becomes an unread-only feed (2026-08-22)**: the hook now
polls `getNotifications(true)` (`?unreadOnly=true`, a parameter the backend already supported — no
backend change), and `markAsRead`/`markAllAsRead` **remove** rows from local state instead of
stamping `readAt` on them. `unreadCount` therefore derives from `notifications.length`. A new
`dismissedIds` ref filters incoming poll data so a row cannot flash back during the window between
the optimistic removal and the `POST` landing; it is a render-time filter only, never sent anywhere,
and deliberately not persisted — the server's own `readAt` is what makes the removal survive a
reload. The optimistic contract is otherwise unchanged: the `POST` is fired and not awaited, and a
failure self-corrects on the next tick with no error toast. Nothing is deleted server-side. Full
rationale and the consumer-side consequence: `features/notifications/README.md`.

## `AuthProvider` — expired-token handling (2026-08-23)

Alongside `setAuthTokenGetter`, the provider now registers `setUnauthorizedHandler`
(`shared/api/httpClient`): any request sent with a token that comes back `401` clears the token
from `localStorage` and drops `token`/`user`, exactly as `logout()` does, which makes
`RequireAuth` send the user to `/login` on the next render.

There is no refresh-token flow in this system (`docs/architecture/api-contract.md` §3.1: one 24h
access token), so ending the session *is* the intended behavior for an expired token — the
previous behavior was to keep a dead token in `localStorage` indefinitely while every write
failed behind a generic error banner. Rehydration's own 401 path is unchanged.

## `useSosRequest` — the expansion action is gone (2026-08-24)

The hook no longer exposes `expandSearch`/`isExpanding`/`expandError`. Widening the SOS search is
server-side and automatic as of MS3 (every two minutes, for as long as the scan window is open, on
a schedule the request itself carries), so there is nothing for a client to trigger — a widened
search simply arrives as more offers and more candidates on the next read, like any other change.

Everything else about the hook is unchanged: REST is canonical, realtime only accelerates it, and
no state transition is ever derived from a browser timer.

## Request-efficiency pass — polling ownership and cadences (2026-08-24)

Every hook in this folder that polls now schedules through `pollingStore.ts` instead of owning a
`setInterval`. The measured problem, the full ownership table and the before/after request counts
live in `docs/architecture/frontend-request-efficiency.md`; what changed *here*:

- **`usePolling`** is now a binding over the shared scheduler (see "Structure" above). Same
  signature, four new optional options, plus visibility suspension and request deduplication for
  every existing caller for free.
- **`useNotifications`** polls only while an order is live, and otherwise not at all — see its
  entry above. This is a product rule, not a tuning change.
- **`ActiveOrderProvider`** paces itself by lifecycle (10s `ON_THE_WAY` / 20s `PENDING`-
  `CONFIRMED` / 60s idle, was a flat 4s) and now also exposes `hasLiveOrder`, which is the
  session's answer to "is anything happening" and what the notification gate reads.
- **`useOrderStatus`** paces itself by lifecycle too, and its `CONFIRMED` cadence depends on how
  near `bookedStart` is: 8s inside 30 minutes of the appointment, 20s beyond it. The flat-20s
  version measured a real regression — `ON_THE_WAY` took 19.9s to reach the screen against ~4s
  before — which is what the proximity rule exists to avoid.
- **`PendingRequestsProvider`** now carries the pending `orders` themselves, not just a count, and
  exposes a ref-counted `setLiveCadence`. `useLivePendingRequests()` is the wrapper a screen uses
  to raise the shared poll to 6s while it is mounted. `IncomingRequestsPage` consumes that instead
  of running its own second poll of the identical URL.
- **`useSosRequest`/`ProSosProvider`** vary their cadence with the realtime socket, which is what
  makes "polling is the fallback" true rather than merely documented: 20s (customer) and 20s/60s
  (professional) while connected, 3s and 5s/20s while not. They are also the only two places
  allowed to poll a hidden tab, and only while the socket is down — an SOS offer's window is about
  two minutes, and with no socket the timer is the only channel left.
- **`AuthProvider`** guards its `GET /api/users/me` bootstrap with a ref, so `StrictMode`'s
  double-invoked mount effect no longer issues it twice, and calls `clearPollingStore()` on logout
  and on the 401 session-end path.

## `useHeaderBackAction` / `HeaderBackProvider` (2026-09-04)

Lets a routed screen render its back control inside `AppLayout`'s header bar instead of as a row
below it. `HeaderBackProvider` holds a single `{ label, onBack }` slot (mounted above the router in
`App.tsx`); `useHeaderBackAction(onBack, label = 'חזרה')` fills it for as long as the screen is
mounted and clears it on unmount.

Two deliberate details. **`HeaderBackContext` is defaulted rather than `undefined`-and-throw**,
unlike `useAuth`/`useToast` whose absence is a real bug: a header slot is decoration, and a page
rendered outside the shell — which is what every component test does — must still render, just
without the hoisted button. And **`onBack` is invoked through a stable wrapper over a ref**, so a
handler that closes over live state (`NewIssuePage`'s reads the current step) stays current without
the registration effect re-running on every keystroke.

`useHeaderBackAction(null)` empties the slot rather than registering a dead button — for a screen
where back is conditional (`BookingFlowPage`'s success step, `ProfessionMatchPage`'s matching phase)
rather than absent. It keeps those callers from having to make the hook call itself conditional.


## Guest draft freshness (2026-09-04) — `sanitizeRestoredDraft`

`updatedAt` was written on every `updateDraft` and read by nobody. It is now the input to one
policy, applied at the single point a draft re-enters the app (`BookingDraftProvider`'s load)
rather than re-asked by each screen's resume effect.

**The bug.** A guest starts a booking, closes the app, comes back the next day, and every screen
decided where to resume from data with no age attached to it: `BookingFlowPage` skips its address
step whenever `stage` is past it and the address fields are non-empty; `ProfessionMatchPage` opens
straight into the matching wheel whenever `isAddressComplete(address)`; `ProntoSosEntryPage` goes
further and *auto-activates a dispatch* against a usable address. Yesterday's address, professional
and time silently became today's booking.

**The threshold is 12 hours**, and guest drafts only (`ownerId === null` — a signed-in customer has
an account to come back to and nothing about their resume was reported wrong). Everything inside one
visit — a refresh, a commute, a phone that slept — resumes exactly as before. The upper bound it
sits under is the backend's 24h guest upload session: past that, a guest's photos are unreachable
with the token that uploaded them anyway.

**Kept** (the customer's account of their problem, which does not go stale): description, category,
photo keys, clarification answers, urgency — so Regular stays Regular and SOS stays SOS — any issue
id, and the address itself. **Dropped** (positions in a flow, not facts): stage → `ADDRESS_SELECTION`,
professional, start time, sort, address mode.

The address is kept as *prefill* and flagged `addressUnconfirmed`, because "do we have an address?"
and "has this customer confirmed it is still where they want somebody sent?" are different
questions, and `isAddressComplete` was standing in for the second one. The flag is what the three
screens above now consult; whichever address step the customer confirms clears it.

## The deferred-authentication gate (2026-09-04) — `AuthGateProvider` / `useAuthGate`

A screen can ask for a session **without leaving itself**: `open(onAuthenticated)` records the action
the customer was refused, `AuthGateModal` (features/auth) renders the existing login/register/OTP
components over the screen, and `useSessionLanding` consumes the gate (`completeInPlace`) instead of
navigating. One landing implementation still, which is the point — the gate is a branch inside it,
not a second copy of it.

Defaulted context rather than throw-on-missing, like `headerBackContext`: a screen rendered outside
the provider (every component test) keeps working, and without a gate the honest fallback is the old
behaviour.
