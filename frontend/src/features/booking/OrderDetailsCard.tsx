import { ChevronLeft } from 'lucide-react';
import { Card, StatusBadge, Skeleton } from '../../shared/components';
import { getCategoryNameHe } from '../../shared/api';
import type { IssueDetailResponse, OrderDetailResponse } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './OrderTrackingPage.module.css';

export interface OrderDetailsCardProps {
  order: OrderDetailResponse;
  /** `undefined` while the follow-up `GET /api/issues/{issueId}` is still in flight. */
  issue: IssueDetailResponse | undefined;
  /** Drives the two role-scoped rows: the professional's inline ETA row and the customer-phone
   *  row (which the backend only populates for a party to the order in the first place). */
  isProfessionalViewer: boolean;
  /** `ON_THE_WAY` ETA countdown, professional view only — omit where there is none to show. */
  remainingMinutes?: number | null;
  isArriving?: boolean;
  /**
   * Opens the assigned professional's profile in place. Provided on the customer's view only —
   * a professional looking at their own job has no profile of a counterparty to open, and the
   * name is their customer's. Omitted ⇒ the name renders as plain text exactly as before.
   */
  onOpenProfessional?: () => void;
}

/**
 * The order-details card: counterparty + order id + status, the issue (category, SOS tag, the
 * customer's own words, photos), date/time, service address, the customer's phone for a
 * professional viewer, and the total.
 *
 * Extracted verbatim from `OrderTrackingPage`'s inline JSX — markup, class names and the
 * role-scoped conditions are unchanged, and the stylesheet stays
 * `OrderTrackingPage.module.css` (same "co-locate on the one consumer's stylesheet" precedent
 * `ReviewList.tsx` follows). The extraction exists so the professional dashboard's inline
 * request-details view (`features/dashboard/RequestDetailsModal`) shows the *same* order
 * details the tracking screen reachable from a notification does, rather than a second,
 * drifting rendition of the same DTO.
 */
export function OrderDetailsCard({
  order,
  issue,
  isProfessionalViewer,
  remainingMinutes = null,
  isArriving = false,
  onOpenProfessional,
}: OrderDetailsCardProps) {
  const counterpartyName = isProfessionalViewer ? order.customerName : order.professionalName;
  const professionalIsOpenable = !isProfessionalViewer && onOpenProfessional != null;

  return (
    <Card className={styles.statusCard}>
      <div className={styles.statusRow}>
        <div>
          {/* The customer's counterparty is a person they can look up — same
              `ProfessionalProfileModal` the orders list opens, in place, never a navigation. */}
          {professionalIsOpenable ? (
            <button
              type="button"
              className={`${styles.professionalName} ${styles.professionalNameButton}`}
              onClick={onOpenProfessional}
              aria-label={`צפייה בפרופיל של ${counterpartyName}`}
            >
              {counterpartyName}
              <ChevronLeft size={16} aria-hidden="true" className={styles.professionalChevron} />
            </button>
          ) : (
            <p className={styles.professionalName}>{counterpartyName}</p>
          )}
          <p className={styles.orderIdLabel}>הזמנה #{order.id}</p>
        </div>
        <StatusBadge status={order.orderStatus} />
      </div>

      {/* The customer's ETA leads the hero on their own screen; the professional's view keeps
          the inline row. */}
      {isProfessionalViewer && order.orderStatus === 'ON_THE_WAY' && remainingMinutes !== null && (
        <div className={styles.row}>
          <span className={styles.rowLabel}>זמן הגעה משוער</span>
          <span className={styles.rowValue}>{isArriving ? 'מגיע/ה עכשיו' : `כ־${remainingMinutes} דקות`}</span>
        </div>
      )}

      <hr className={styles.divider} />

      <div className={styles.headerRow}>
        {issue ? (
          <span className={styles.category}>{getCategoryNameHe(issue.categoryId)}</span>
        ) : (
          <Skeleton variant="rect" radius="var(--radius-sm)" className={styles.categorySkeleton} />
        )}
        {issue?.urgencyType === 'SOS' && <span className={styles.sosTag}>SOS</span>}
      </div>

      {/* Labelled only for the professional: on the customer's own screen "מה הלקוח תיאר"
          would be redundant, but on the professional's it is what keeps the customer's words
          distinguishable from the Pronto analysis card. */}
      {issue && isProfessionalViewer && <p className={styles.reportLabel}>מה הלקוח תיאר</p>}
      {issue && <p className={styles.description}>“{issue.description}”</p>}

      {issue && issue.images.length > 0 && (
        <div className={styles.photoRow}>
          {issue.images.map((image) => (
            <div key={image.id} className={styles.photoThumbWrapper}>
              <img src={image.imageUrl} alt="" className={styles.photoThumb} />
            </div>
          ))}
        </div>
      )}

      <hr className={styles.divider} />

      <div className={styles.row}>
        <span className={styles.rowLabel}>תאריך ושעה</span>
        <span className={styles.rowValue}>
          {formatDateLabel(order.bookedStart)}, {formatTimeLabel(order.bookedStart)}
          {order.bookedEnd ? `–${formatTimeLabel(order.bookedEnd)}` : ''}
        </span>
      </div>

      <div className={styles.row}>
        <span className={styles.rowLabel}>כתובת</span>
        <span className={styles.rowValue}>
          {order.serviceCity}, {order.serviceStreet} {order.serviceHouseNumber}
          {order.serviceApartment ? `, דירה ${order.serviceApartment}` : ''}
          {order.serviceFloor ? `, קומה ${order.serviceFloor}` : ''}
          {order.serviceEntrance ? `, כניסה ${order.serviceEntrance}` : ''}
        </span>
        {order.serviceAddressNotes && <span className={styles.rowValue}>{order.serviceAddressNotes}</span>}
      </div>

      {isProfessionalViewer && order.customerPhone && (
        <div className={styles.row}>
          <span className={styles.rowLabel}>טלפון הלקוח</span>
          <span className={styles.rowValue}>{order.customerPhone}</span>
        </div>
      )}

      <hr className={styles.divider} />

      <div className={styles.totalRow}>
        <span className={styles.totalLabel}>סה״כ</span>
        <span className={styles.totalPrice}>₪{order.finalPrice}</span>
      </div>
    </Card>
  );
}
