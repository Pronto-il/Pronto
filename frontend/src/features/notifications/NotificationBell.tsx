import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell } from 'lucide-react';
import { useNotifications } from '../../shared/hooks';
import type { NotificationResponse } from '../../shared/api';
import { formatDateTimeLabel } from '../../shared/utils/formatDateTime';
import { getMessageTypeLabel } from './notificationLabels';
import styles from './NotificationBell.module.css';

/** Unread counts above this display as `9+` on the badge, per the design brief. */
const MAX_BADGE_COUNT = 9;

/**
 * In-app notification bell (Frontend Milestone 5): a nav button with an unread-count badge
 * that toggles a dropdown panel, backed by `useNotifications()` (`GET /api/notifications`,
 * short-polling — no business logic here beyond mapping state to markup). Rendered for both
 * roles in `AppLayout`'s nav, unlike `ActiveOrderIndicator` (CUSTOMER-only) — `notifications`
 * is an either-role feed (`NotificationController`'s routes are self-scoped by caller, no
 * role gate).
 *
 * A dedicated page/route was deliberately not built (design brief) — the backend feed has no
 * pagination, so a lightweight anchored popover is enough. Click-outside (via a `mousedown`
 * listener on `document`, scoped to while the panel is open) or a second bell click closes
 * the panel; clicking a row marks it read optimistically and navigates to
 * `/orders/{relatedOrderId}` (added in Frontend Milestone 3, works for either role).
 */
export function NotificationBell() {
  const navigate = useNavigate();
  const { notifications, unreadCount, markAsRead, markAllAsRead } = useNotifications();
  const [isOpen, setIsOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    function handleClickOutside(event: MouseEvent) {
      if (wrapperRef.current && !wrapperRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isOpen]);

  function handleRowClick(notification: NotificationResponse) {
    // Optimistic mark-read + navigate immediately — the background `POST` isn't awaited,
    // per the design brief (low-stakes, next poll tick self-corrects on failure).
    markAsRead(notification.id);
    setIsOpen(false);
    navigate(`/orders/${notification.relatedOrderId}`);
  }

  const badgeText = unreadCount > MAX_BADGE_COUNT ? '9+' : String(unreadCount);

  return (
    <div className={styles.wrapper} ref={wrapperRef}>
      <button
        type="button"
        className={styles.bellButton}
        onClick={() => setIsOpen((prev) => !prev)}
        aria-label="התראות"
      >
        <Bell size={18} aria-hidden="true" />
        {unreadCount > 0 && <span className={styles.badge}>{badgeText}</span>}
      </button>

      {isOpen && (
        <div className={styles.panel}>
          <div className={styles.panelHeader}>
            <span className={styles.panelTitle}>התראות</span>
            <button
              type="button"
              className={styles.markAllButton}
              onClick={markAllAsRead}
              disabled={unreadCount === 0}
            >
              סמן הכל כנקרא
            </button>
          </div>

          {notifications.length === 0 ? (
            <div className={styles.emptyState}>אין התראות חדשות</div>
          ) : (
            <ul className={styles.list}>
              {notifications.map((notification) => (
                <li key={notification.id}>
                  <button
                    type="button"
                    className={`${styles.row} ${notification.readAt === null ? styles.unread : ''}`}
                    onClick={() => handleRowClick(notification)}
                  >
                    <span className={styles.rowLabel}>{getMessageTypeLabel(notification.messageType)}</span>
                    <span className={styles.rowTime}>{formatDateTimeLabel(notification.createdAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
