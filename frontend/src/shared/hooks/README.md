# shared/hooks

## Purpose
Reusable React hooks shared across features.

## Responsibilities
- Auth context/hook (current user, token, login/logout).
- Short-polling status hook (per `docs/architecture/overview.md` §3.3) used by the
  booking tracking screen and the professional's incoming-request feed — shipped in
  Frontend Milestone 3 (2026-08-16).
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
- `usePolling.ts` — the generic short-polling hook: fetches immediately on mount, then
  re-fetches every `intervalMs` (default 4000ms). Skips a tick if the previous request is
  still in flight (never overlaps requests), cleans up its interval on unmount, and is a
  no-op while `enabled` is `false`. Backing implementation for `useOrderStatus` and any
  other future polling need, per `docs/architecture/overview.md` §3.3 (short-polling, not
  WebSocket).
- `useOrderStatus.ts` — order-tracking-screen polling wrapper around `usePolling`, built
  on `shared/api/bookings.ts`'s `getOrder` (`GET /api/bookings/orders/{orderId}`). Stops
  polling once the last-observed `orderStatus` reaches a terminal state (`COMPLETED`/
  `CANCELLED`/`REJECTED`/`EXPIRED`) — no point short-polling a status that can never
  change again. Consumed by `features/booking/OrderTrackingPage.tsx`; the professional's
  incoming-request feed (`features/dashboard/IncomingRequestsPage.tsx`) consumes
  `usePolling` directly instead (it polls a list endpoint, not a single order).
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
  and backward, in `NewIssuePage`/`BookingFlowPage`/`SosBookingFlowPage`. `clearDraft()` has
  exactly two call sites: each booking flow page's post-order-creation success handler, and
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
- `useNotifications.ts` — **new, Frontend Milestone 5.** Polling wrapper around
  `usePolling` (`GET /api/notifications` via `shared/api/notifications.ts`'s
  `getNotifications`, default 4s interval, no `unreadOnly` filter). Deliberately a plain
  hook, not a React context: unlike `useBookingDraft`/`useActiveOrder` it has exactly one
  consumer (`features/notifications/NotificationBell.tsx`), no cross-page state to
  coordinate. Exposes `{ notifications, unreadCount, isLoading, markAsRead(id),
  markAllAsRead() }`. `unreadCount` is derived client-side from `notifications`'s `readAt`
  values (equivalent to the poll response's own `unreadCount` field, since the feed is
  always unfiltered) rather than tracked as separate state, so an optimistic
  `markAsRead`/`markAllAsRead` update is instantly reflected in the badge with nothing else
  to keep in sync. Both mutations update local state immediately and fire their `POST` in
  the background without awaiting it or forcing a `refetch()` afterwards — a failed request
  just self-corrects on the next poll tick (no error toast, low-stakes action).
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
