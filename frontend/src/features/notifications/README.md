# features/notifications

## Purpose
In-app notification bell: a nav button with an unread-count badge and an anchored dropdown
panel, consuming the backend's `notifications` package
(`backend/src/main/java/com/pronto/notifications/`) via short-polling.

## Responsibilities
- `NotificationBell.tsx` — the bell button + badge + dropdown panel. Presentation only; all
  data-fetching, polling, and optimistic mark-read state lives in
  `shared/hooks/useNotifications.ts`, not here.
- `notificationLabels.ts` — Hebrew label lookup for the 8-member `NotificationMessageType`
  enum, with an explicit fallback for any value not in the map (defensive against a future
  backend enum addition landing before the frontend mirrors it).
- No dedicated route/page: the backend feed has no pagination, so a lightweight popover
  anchored to the bell (not a full screen) is enough (design brief, Frontend Milestone 5).

## Consumes
- `shared/hooks/useNotifications` — polling wrapper around `GET /api/notifications`
  (`shared/hooks/usePolling`, default 4s interval), exposing `notifications`, `unreadCount`
  (derived from the local feed's `readAt` values), `markAsRead(id)`, `markAllAsRead()`. Both
  mutations are optimistic (local state updates immediately, the `POST` fires in the
  background and isn't awaited) — a failed request self-corrects on the next poll tick, no
  error toast.
- `shared/api/notifications` — typed wrappers for `GET /api/notifications`,
  `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all`.
- `shared/utils/formatDateTime` (`formatDateTimeLabel`) — reused for each row's timestamp,
  not reimplemented here.

## Rendered from
`app/AppLayout.tsx`'s authenticated nav, for **both** roles (CUSTOMER and PROFESSIONAL) —
unlike `ActiveOrderIndicator` (CUSTOMER-only), since `GET /api/notifications` is an
either-role, self-scoped feed. Positioned right after `BookingDraftIndicator` and before the
role-conditional `/orders`/`/pro` link.

## Status
Implemented in **Milestone 5 — Notifications & real-time status**
(`docs/architecture/implementation-plan.md`). Real-time status *updates on the order itself*
(the Pending/Confirmed/On the Way/Completed/Cancelled/Expired lifecycle) are handled
separately by `features/booking`'s `OrderTrackingPage` (`shared/hooks/useOrderStatus`) — this
module is only the notification feed/bell, per its original stub description.
