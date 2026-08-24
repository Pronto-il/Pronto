import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell } from 'lucide-react';
import { useAuth, useNotifications } from '../../shared/hooks';
import type { NotificationResponse } from '../../shared/api';
import { formatDateTimeLabel } from '../../shared/utils/formatDateTime';
import { getMessageTypeLabel } from './notificationLabels';
import styles from './NotificationBell.module.css';

/** Unread counts above this display as `9+` on the badge, per the design brief. */
const MAX_BADGE_COUNT = 9;

/**
 * In-app notification bell (Frontend Milestone 5): a nav button with an unread-count badge
 * that toggles a dropdown panel, backed by `useNotifications()` (`GET /api/notifications` — no
 * business logic here beyond mapping state to markup). Rendered for both
 * roles in `AppLayout`'s nav, unlike `ActiveOrderIndicator` (CUSTOMER-only) — `notifications`
 * is an either-role feed (`NotificationController`'s routes are self-scoped by caller, no
 * role gate).
 *
 * **When the feed is read is `useNotifications`'s decision, not this component's** — it polls
 * only while an order is live, and otherwise reads once at bootstrap and once per panel open.
 * All this component contributes to that is `isPanelOpen`.
 *
 * A dedicated page/route was deliberately not built (design brief) — the backend feed has no
 * pagination, so a lightweight anchored popover is enough. Click-outside (via a `mousedown`
 * listener on `document`, scoped to while the panel is open) or a second bell click closes
 * the panel; clicking a row marks it read optimistically and navigates to
 * `/orders/{relatedOrderId}` (added in Frontend Milestone 3, works for either role).
 *
 * **The panel shows unread notifications only** — reading one removes it here and from the badge
 * immediately, and it does not come back on refresh. That rule lives entirely in
 * `useNotifications`; see its doc comment for why the row is dropped from the feed rather than
 * deleted server-side. Every row this component renders is therefore unread by construction,
 * which is why the row style is unconditional and the empty state reads אין התראות חדשות.
 */
export function NotificationBell() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [isOpen, setIsOpen] = useState(false);
  // Opening the panel is what tells `useNotifications` to read the feed — see its doc comment.
  // While no order is live nothing polls at all, so this click is the refresh.
  const { notifications, unreadCount, markAsRead, markAllAsRead } = useNotifications({ isPanelOpen: isOpen });
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

  /**
   * Where a row leads, or `null` when there is nowhere honest to send the reader.
   *
   * Three cases, one per audience:
   *
   * - **Order rows** go to `/orders/{id}`, unchanged and untouched by any of this.
   * - **A professional's SOS row** goes to `/pro/sos` — their offer inbox and active job, which is
   *   the right destination for every professional-facing SOS type and needs no id.
   * - **A customer's SOS row** goes to `/issues/{relatedIssueId}/sos-booking`, their live SOS
   *   screen. That route is keyed by *issue* rather than by SOS request, deliberately: one problem
   *   accumulates many attempts, and the customer should land on where their problem stands now.
   *
   * The customer case is the one that used to be broken. The row carries `relatedSosRequestId`
   * (the correct subject to store — it is FK-constrained to `sos_requests`), which was a subject
   * with no destination, so every customer SOS notification was a dead end. `relatedIssueId` is
   * derived server-side from that request at read time — no duplicated state, no second column.
   * It can still be `null` for a request that no longer resolves, and a dead end remains better
   * than a wrong link, so that case still returns `null`.
   */
  function destinationFor(notification: NotificationResponse): string | null {
    if (notification.relatedOrderId !== null) {
      return `/orders/${notification.relatedOrderId}`;
    }
    if (notification.relatedSosRequestId !== null) {
      if (user?.role === 'PROFESSIONAL') {
        return '/pro/sos';
      }
      if (notification.relatedIssueId !== null) {
        return `/issues/${notification.relatedIssueId}/sos-booking`;
      }
    }
    return null;
  }

  function handleRowClick(notification: NotificationResponse) {
    // Optimistic mark-read + navigate immediately — the background `POST` isn't awaited,
    // per the design brief (low-stakes, next poll tick self-corrects on failure).
    markAsRead(notification.id);
    setIsOpen(false);
    const destination = destinationFor(notification);
    if (destination !== null) {
      navigate(destination);
    }
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
                    className={`${styles.row} ${styles.unread}`}
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
