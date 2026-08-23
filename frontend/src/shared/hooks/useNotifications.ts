import { useCallback, useEffect, useRef, useState } from 'react';
import { getNotifications, markNotificationRead, markAllNotificationsRead } from '../api/notifications';
import type { NotificationResponse, NotificationsListResponse } from '../api/notifications';
import { usePolling } from './usePolling';

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
 * Notification-bell polling wrapper around `usePolling` (`GET /api/notifications?unreadOnly=true`,
 * default 4s interval). Plain hook, not a React Context: unlike `useActiveOrder`/`useBookingDraft`
 * this has exactly one consumer (`NotificationBell` itself), no cross-page state to coordinate.
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
 *   `POST` is fired and not awaited, and a failure self-corrects on a later tick (no error toast —
 *   this is a low-stakes action).
 *
 * `dismissedIds` closes the gap between those two halves. Between the optimistic removal and the
 * `POST` landing, an in-flight poll can still be carrying the row as unread; without this set it
 * would flash back into the panel for one tick. It is a render-time filter over incoming poll data
 * only — ids are never sent anywhere, and it is intentionally not persisted, because the server's
 * own `readAt` is what makes the removal survive a reload.
 *
 * `unreadCount` is derived from `notifications.length` rather than from the poll response's own
 * `unreadCount` field, so the badge inherits the same optimistic update as the list and there is no
 * second piece of state to keep in sync by hand.
 */
export function useNotifications(): UseNotificationsResult {
  const { data, isLoading } = usePolling<NotificationsListResponse>(() => getNotifications(true));

  const [notifications, setNotifications] = useState<NotificationResponse[]>([]);
  const dismissedIds = useRef<Set<number>>(new Set());

  useEffect(() => {
    if (data) {
      setNotifications(data.notifications.filter((notification) => !dismissedIds.current.has(notification.id)));
    }
  }, [data]);

  const unreadCount = notifications.length;

  const markAsRead = useCallback((id: number) => {
    dismissedIds.current.add(id);
    setNotifications((prev) => prev.filter((notification) => notification.id !== id));
    void markNotificationRead(id).catch(() => {
      // Swallow — low-stakes, next poll tick self-corrects (no error toast, per design).
      dismissedIds.current.delete(id);
    });
  }, []);

  const markAllAsRead = useCallback(() => {
    setNotifications((prev) => {
      prev.forEach((notification) => dismissedIds.current.add(notification.id));
      return [];
    });
    void markAllNotificationsRead().catch(() => {
      // Swallow — low-stakes, next poll tick self-corrects (no error toast, per design).
      dismissedIds.current.clear();
    });
  }, []);

  return { notifications, unreadCount, isLoading, markAsRead, markAllAsRead };
}
