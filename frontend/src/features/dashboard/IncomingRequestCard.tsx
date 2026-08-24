import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { Card, Button } from '../../shared/components';
import { getCategoryNameHe } from '../../shared/api';
import type { IssueDetailResponse, OrderSummary } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import { toastTransition } from '../../shared/motion/variants';
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
  /**
   * MS6 Professional Command Center design doc §4.3: `true` when this order id wasn't present
   * in the previous poll tick's result — plays a one-shot entrance animation. `false` for
   * every already-seen card, which mounts with no animation (no persistent pulse/glow on
   * existing cards, per `DESIGN_SYSTEM.md` §91).
   */
  isNew: boolean;
  /** Opens the inline request-details view (`RequestDetailsModal`). The whole card is the
   *  affordance — everything except the two decision buttons, which keep their own actions. */
  onOpenDetails: (orderId: number) => void;
}

/**
 * New-request card, per DESIGN_SYSTEM.md §55: category, description quote, date/time,
 * decision-critical info immediately visible. No location/distance line — no endpoint
 * returns that for an order, so it isn't fabricated. SOS orders are a real, reachable case
 * as of Frontend Milestone 4 (the customer-facing SOS booking flow) — the `sosTag` below
 * already renders per DESIGN_SYSTEM.md §55, and `order.bookedEnd == null` (SOS orders have
 * no scheduled end time) is already handled gracefully in the time row.
 *
 * **MS6**: a newly-appeared card (`isNew`, computed by `IncomingRequestsPage` via order-id
 * diffing across poll ticks) gets a one-shot `framer-motion` entrance, reusing
 * `toastTransition`'s mount shape (opacity/y/scale spring) — the "meaningful product motion"
 * tier per `shared/motion/README.md`, since a new order arriving is a real state change, not a
 * hover/press micro-interaction (design doc §4.3). Already-seen cards render with no animation.
 */
export function IncomingRequestCard({
  order,
  issue,
  isAccepting,
  isRejecting,
  onAccept,
  onReject,
  isNew,
  onOpenDetails,
}: IncomingRequestCardProps) {
  const isProcessing = isAccepting || isRejecting;

  const shouldReduceMotion = useReducedMotion();
  const cardAnimate = shouldReduceMotion
    ? { ...(toastTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  return (
    <motion.div variants={toastTransition} initial={isNew ? 'initial' : false} animate={cardAnimate}>
      <Card className={styles.card}>
        {/* The card body opens the full request inline (design: never leave the feed). It's a
            `div` with button semantics rather than a real `<button>` because it wraps
            headings, an image row and other block content a button may not contain; the two
            decision buttons below sit outside it, so they keep their own click targets with
            no `stopPropagation` juggling. */}
        <div
          className={styles.clickableBody}
          role="button"
          tabIndex={0}
          aria-label="פתיחת פרטי הבקשה"
          onClick={() => onOpenDetails(order.id)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              onOpenDetails(order.id);
            }
          }}
        >
          <div className={styles.headerRow}>
            {issue ? (
              <span className={styles.category}>{getCategoryNameHe(issue.categoryId)}</span>
            ) : (
              <span className={styles.skeleton} />
            )}
            {issue?.urgencyType === 'SOS' && <span className={styles.sosTag}>SOS</span>}
          </div>

          {issue && <p className={styles.description}>“{issue.description}”</p>}

          {/* One line, not the full brief: this is the accept/reject card, and "what is this
              likely to be" is the single piece of Pronto analysis that changes that decision.
              The full brief now lives one tap away, in the inline details view. Rendered only
              when a hypothesis actually survived validation — a PENDING or evidence-less brief
              shows nothing rather than a placeholder. */}
          {issue?.prontoAnalysis?.likelyIssue && (
            <p className={styles.analysisLine}>
              <span className={styles.analysisLabel}>ניתוח Pronto:</span>{' '}
              {issue.prontoAnalysis.likelyIssue.description}
            </p>
          )}

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

          <p className={styles.detailsHint}>לצפייה בכל פרטי הבקשה — לחצו על הכרטיס</p>
        </div>

        <div className={styles.actions}>
          <Button variant="secondary" onClick={() => onReject(order.id)} disabled={isProcessing} loading={isRejecting}>
            דחייה
          </Button>
          <Button onClick={() => onAccept(order.id)} disabled={isProcessing} loading={isAccepting}>
            אישור
          </Button>
        </div>
      </Card>
    </motion.div>
  );
}
