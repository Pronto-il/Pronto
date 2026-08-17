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
 * returns that for an order, so it isn't fabricated. SOS orders aren't produced by this
 * frontend yet (SOS booking isn't built), but the tag is shown defensively if one ever
 * appears (e.g. seeded directly in the DB), per FRONTEND_AGENT.md §13.
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
