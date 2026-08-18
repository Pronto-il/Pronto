import { useCallback, useEffect, useState } from 'react';
import { getNotifications, markNotificationRead, markAllNotificationsRead } from '../api/notifications';
import type { NotificationResponse, NotificationsListResponse } from '../api/notifications';
import { usePolling } from './usePolling';

export interface UseNotificationsResult {
  /** Full feed, most-recent-first (backend orders by `createdAt DESC`), no pagination. */
  notifications: NotificationResponse[];
  /** Derived from `notifications` (count of `readAt === null` rows), not the raw poll response — see note below. */
  unreadCount: number;
  isLoading: boolean;
  /** Optimistically marks one notification read locally, then fires the `POST` in the background. */
  markAsRead: (id: number) => void;
  /** Optimistically marks every notification read locally, then fires the `POST` in the background. */
  markAllAsRead: () => void;
}

/**
 * Notification-bell polling wrapper around `usePolling` (`GET /api/notifications`, default
 * 4s interval — no custom interval passed, and no `unreadOnly` filter, since the dropdown
 * panel renders the full feed). Plain hook, not a React Context: unlike `useActiveOrder`/
 * `useBookingDraft` this has exactly one consumer (`NotificationBell` itself), no cross-page
 * state to coordinate.
 *
 * `unreadCount` is derived from the local `notifications` state (`readAt === null` count)
 * rather than passed through from the poll response's own `unreadCount` field — the two are
 * equivalent (the feed always contains every notification, unfiltered) but deriving locally
 * means an optimistic `markAsRead`/`markAllAsRead` update is instantly reflected in the badge
 * without a second piece of state to keep in sync by hand.
 *
 * `markAsRead`/`markAllAsRead` update local state immediately and fire the corresponding
 * `POST` in the background — they don't block on the network round trip, and deliberately
 * don't force an extra `refetch()` afterwards (the optimistic update already reflects the
 * outcome; a failed request just self-corrects on the next poll tick, no error toast — this
 * is a low-stakes action).
 */
export function useNotifications(): UseNotificationsResult {
  const { data, isLoading } = usePolling<NotificationsListResponse>(() => getNotifications());

  const [notifications, setNotifications] = useState<NotificationResponse[]>([]);

  useEffect(() => {
    if (data) {
      setNotifications(data.notifications);
    }
  }, [data]);

  const unreadCount = notifications.filter((notification) => notification.readAt === null).length;

  const markAsRead = useCallback((id: number) => {
    setNotifications((prev) =>
      prev.map((notification) =>
        notification.id === id && notification.readAt === null
          ? { ...notification, readAt: new Date().toISOString() }
          : notification,
      ),
    );
    void markNotificationRead(id).catch(() => {
      // Swallow — low-stakes, next poll tick self-corrects (no error toast, per design).
    });
  }, []);

  const markAllAsRead = useCallback(() => {
    setNotifications((prev) =>
      prev.map((notification) =>
        notification.readAt === null ? { ...notification, readAt: new Date().toISOString() } : notification,
      ),
    );
    void markAllNotificationsRead().catch(() => {
      // Swallow — low-stakes, next poll tick self-corrects (no error toast, per design).
    });
  }, []);

  return { notifications, unreadCount, isLoading, markAsRead, markAllAsRead };
}
