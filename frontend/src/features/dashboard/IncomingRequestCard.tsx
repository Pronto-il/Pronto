import { Card, Button } from '../../shared/components';
import { getCategoryNameHe } from '../../shared/api';
import type { IssueDetailResponse, OrderSummary } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './IncomingRequestCard.module.css';

export interface IncomingRequestCardProps {
  order: OrderSummary;
  /** `undefined` while the follow-up `GET /api/issues/{issueId}` call is still in flight. */
  issue: IssueDetailResponse | undefined;
  /** Whether the accept action for this order is currently in flight (spinner on "אישור"). */
  isAccepting: boolean;
  /** Whether the reject action for this order is currently in flight (spinner on "דחייה"). */
  isRejecting: boolean;
  onAccept: (orderId: number) => void;
  onReject: (orderId: number) => void;
}

/**
 * New-request card, per DESIGN_SYSTEM.md §55: category, description quote, date/time,
 * decision-critical info immediately visible. No location/distance line — no endpoint
 * returns that for an order, so it isn't fabricated. SOS orders are a real, reachable case
 * as of Frontend Milestone 4 (the customer-facing SOS booking flow) — the `sosTag` below
 * already renders per DESIGN_SYSTEM.md §55, and `order.bookedEnd == null` (SOS orders have
 * no scheduled end time) is already handled gracefully in the time row.
 */
export function IncomingRequestCard({
  order,
  issue,
  isAccepting,
  isRejecting,
  onAccept,
  onReject,
}: IncomingRequestCardProps) {
  const isProcessing = isAccepting || isRejecting;

  return (
    <Card className={styles.card}>
      <div className={styles.headerRow}>
        {issue ? (
          <span className={styles.category}>{getCategoryNameHe(issue.categoryId)}</span>
        ) : (
          <span className={styles.skeleton} />
        )}
        {issue?.urgencyType === 'SOS' && <span className={styles.sosTag}>SOS</span>}
      </div>

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

      <div className={styles.timeRow}>
        <span className={styles.timeDate}>{formatDateLabel(order.bookedStart)}</span>
        <span className={styles.timeRange}>
          {formatTimeLabel(order.bookedStart)}
          {order.bookedEnd ? `–${formatTimeLabel(order.bookedEnd)}` : ''}
        </span>
      </div>

      <p className={styles.priceRow}>₪{order.finalPrice}</p>

      <div className={styles.actions}>
        <Button variant="secondary" onClick={() => onReject(order.id)} disabled={isProcessing} loading={isRejecting}>
          דחייה
        </Button>
        <Button onClick={() => onAccept(order.id)} disabled={isProcessing} loading={isAccepting}>
          אישור
        </Button>
      </div>
    </Card>
  );
}
