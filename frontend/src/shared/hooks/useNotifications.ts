import { useCallback, useEffect, useRef, useState } from 'react';
import { getNotifications, markNotificationRead, markAllNotificationsRead } from '../api/notifications';
import type { NotificationResponse, NotificationsListResponse } from '../api/notifications';
import { NOTIFICATIONS_UNREAD_KEY } from '../api/resourceKeys';
import { useActiveOrder } from './useActiveOrder';
import { usePolling } from './usePolling';

/** Cadence while an order is live and the bell is closed. */
const ACTIVE_ORDER_INTERVAL_MS = 15_000;
/** While the panel is open the list itself is on screen, so it is worth a little more freshness. */
const PANEL_OPEN_INTERVAL_MS = 10_000;

export interface UseNotificationsOptions {
  /** Whether the bell's dropdown is currently open. Opening it triggers one read. */
  isPanelOpen?: boolean;
}

export interface UseNotificationsResult {
  /** The **unread** feed, most-recent-first (backend orders by `createdAt DESC`), no pagination. */
  notifications: NotificationResponse[];
  /** Derived from `notifications.length` — the feed is unread-only, so the two are the same thing. */
  unreadCount: number;
  isLoading: boolean;
  /** Optimistically drops one notification from the visible feed, then fires the `POST` in the background. */
  markAsRead: (id: number) => void;
  /** Optimistically empties the visible feed, then fires the `POST` in the background. */
  markAllAsRead: () => void;
}

/**
 * Notification-bell state (`GET /api/notifications?unreadOnly=true`). Plain hook, not a React
 * Context: unlike `useActiveOrder`/`useBookingDraft` this has exactly one consumer
 * (`NotificationBell` itself), no cross-page state to coordinate. The `usePolling` key still
 * makes the request itself shared, so a second bell mounting anywhere could never double it.
 *
 * <h2>When it polls, and when it does not</h2>
 *
 * **Only while an order is live.** Nothing in this product creates a notification outside an
 * order's lifecycle — every `messageType` the backend sends is an order or SOS transition — so a
 * customer idling with nothing booked has no reason to be asking. The gate is
 * `useActiveOrder().hasLiveOrder`, read from the context the floating indicator already
 * maintains: no request is made to decide whether to make requests, which is the only way this
 * rule saves anything. When an order starts, polling starts with it; when it reaches a terminal
 * state, `hasLiveOrder` goes false and the interval is torn down rather than slowed.
 *
 * A `PROFESSIONAL` (or `ADMIN`) session has no active-order context — `ActiveOrderProvider`
 * polls for `CUSTOMER` only — so `hasLiveOrder` is false for them and the bell never polls.
 * Their live signals arrive on the surfaces that own them: `ProSosProvider`'s socket and toast
 * for SOS offers, and the pending-request badge under `/pro/*`.
 *
 * **One read at bootstrap regardless.** The badge has to be right on first paint for every
 * authenticated session, so the hook asks once on mount (`fetchOnMountWhenDisabled`) and, if the
 * key already holds data, not even that. That read is not an interval and never becomes one.
 * Opening the panel triggers one more, because that is a user action asking to see the list.
 *
 * <h2>The bell is a self-cleaning inbox, not a history</h2>
 *
 * Reading a notification removes it from the visible feed. This is a **presentation** rule
 * enforced on both sides of the poll, and it deliberately deletes nothing:
 *
 * - **The request is `unreadOnly=true`**, so a page refresh, a remount, or a new tab never brings
 *   an already-read notification back. The row still exists server-side (`readAt` is set, not
 *   removed) and stays available for operational/debug/audit purposes — no destructive delete and
 *   no background cleanup job was introduced for this behaviour.
 * - **`markAsRead` removes the row locally at once**, so the list and the badge both update on the
 *   click rather than on the next poll tick. That preserves the existing optimistic contract: the
 *   `POST` is fired and not awaited, and a failure self-corrects on a later read (no error toast —
 *   this is a low-stakes action).
 *
 * `dismissedIds` closes the gap between those two halves. Between the optimistic removal and the
 * `POST` landing, an in-flight read can still be carrying the row as unread; without this set it
 * would flash back into the panel. It is a render-time filter over incoming data only — ids are
 * never sent anywhere, and it is intentionally not persisted, because the server's own `readAt`
 * is what makes the removal survive a reload.
 *
 * `unreadCount` is derived from `notifications.length` rather than from the response's own
 * `unreadCount` field, so the badge inherits the same optimistic update as the list and there is
 * no second piece of state to keep in sync by hand.
 */
export function useNotifications({ isPanelOpen = false }: UseNotificationsOptions = {}): UseNotificationsResult {
  const { hasLiveOrder } = useActiveOrder();

  const { data, isLoading, refetch } = usePolling<NotificationsListResponse>(() => getNotifications(true), {
    key: NOTIFICATIONS_UNREAD_KEY,
    enabled: hasLiveOrder,
    intervalMs: isPanelOpen ? PANEL_OPEN_INTERVAL_MS : ACTIVE_ORDER_INTERVAL_MS,
    fetchOnMountWhenDisabled: true,
  });

  const [notifications, setNotifications] = useState<NotificationResponse[]>([]);
  const dismissedIds = useRef<Set<number>>(new Set());

  useEffect(() => {
    if (data) {
      setNotifications(data.notifications.filter((notification) => !dismissedIds.current.has(notification.id)));
    }
  }, [data]);

  // Opening the panel is a request to see the current list, so it is worth one read — including
  // (especially) in the idle case, where nothing has been polling in the background.
  const wasPanelOpen = useRef(isPanelOpen);
  useEffect(() => {
    if (isPanelOpen && !wasPanelOpen.current) {
      refetch();
    }
    wasPanelOpen.current = isPanelOpen;
  }, [isPanelOpen, refetch]);

  const unreadCount = notifications.length;

  const markAsRead = useCallback((id: number) => {
    dismissedIds.current.add(id);
    setNotifications((prev) => prev.filter((notification) => notification.id !== id));
    void markNotificationRead(id).catch(() => {
      // Swallow — low-stakes, the next read self-corrects (no error toast, per design).
      dismissedIds.current.delete(id);
    });
  }, []);

  const markAllAsRead = useCallback(() => {
    setNotifications((prev) => {
      prev.forEach((notification) => dismissedIds.current.add(notification.id));
      return [];
    });
    void markAllNotificationsRead().catch(() => {
      // Swallow — low-stakes, the next read self-corrects (no error toast, per design).
      dismissedIds.current.clear();
    });
  }, []);

  return { notifications, unreadCount, isLoading, markAsRead, markAllAsRead };
}
