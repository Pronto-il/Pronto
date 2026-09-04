import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PageHeader, Card, Button, Textarea, Skeleton, Mascot } from '../../shared/components';
import { StarRatingInput } from './StarRatingInput';
import { useActiveOrder, useHeaderBackAction } from '../../shared/hooks';
import {
  getOrder,
  createReview,
  ApiError,
  GENERIC_ERROR_MESSAGE,
  REVIEW_COMMENT_MAX_LENGTH,
} from '../../shared/api';
import type { OrderDetailResponse } from '../../shared/api';
import { formatDateLabel } from '../../shared/utils/formatDateTime';
import styles from './CompletionReviewPage.module.css';

const SUBMIT_ERROR_MESSAGES: Record<string, string> = {
  REVIEW_ORDER_NOT_COMPLETED: 'לא ניתן להשאיר ביקורת על הזמנה שטרם הושלמה.',
};

/**
 * `/orders/:orderId/review` — CUSTOMER only (`active-booking-floating-indicator.md` §8/§12).
 * One-shot `getOrder(orderId)` fetch (no polling — the order is already terminal by the
 * time this screen is reachable). Reachable both via the floating indicator (only when it
 * is the currently-selected order) and via direct navigation (`OrderTrackingPage`'s "leave
 * a review" link, or a stale/bookmarked URL), so it re-verifies `orderStatus === 'COMPLETED'`
 * itself rather than trusting the caller.
 *
 * Calls `acknowledgeOrder` (§6.2) twice, independently: once on mount as soon as the fetch
 * confirms `COMPLETED` (merely viewing this screen counts as acknowledging — a review is
 * NOT mandatory), and again after a successful submit (idempotent, kept as its own explicit
 * call per the design doc's reasoning).
 */
export default function CompletionReviewPage() {
  const navigate = useNavigate();
  const { orderId: orderIdParam } = useParams<{ orderId: string }>();
  const orderId = Number(orderIdParam);
  const { acknowledgeOrder } = useActiveOrder();

  const [order, setOrder] = useState<OrderDetailResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [alreadyReviewed, setAlreadyReviewed] = useState(false);
  const [submitted, setSubmitted] = useState(false);

  const acknowledgedRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    getOrder(orderId)
      .then((response) => {
        if (!cancelled) {
          setOrder(response);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLoadError(true);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setIsLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [orderId]);

  useEffect(() => {
    if (order?.orderStatus === 'COMPLETED' && !acknowledgedRef.current) {
      acknowledgedRef.current = true;
      acknowledgeOrder(orderId);
    }
  }, [order, orderId, acknowledgeOrder]);

  async function handleSubmit() {
    if (rating < 1) {
      return;
    }
    setSubmitError(null);
    setIsSubmitting(true);
    try {
      await createReview({ orderId, rating, comment: comment.trim() || undefined });
      acknowledgeOrder(orderId);
      setSubmitted(true);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'REVIEW_ALREADY_EXISTS') {
        setAlreadyReviewed(true);
      } else if (err instanceof ApiError && SUBMIT_ERROR_MESSAGES[err.code]) {
        setSubmitError(SUBMIT_ERROR_MESSAGES[err.code]);
      } else {
        setSubmitError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  /** "לא עכשיו" — dismiss without reviewing. The order is acknowledged (it already is, on
   *  mount, but this keeps the intent explicit) so the floating prompt does not re-offer it, and
   *  the customer is returned to their orders instead of having to use the back button. */
  function handleNotNow() {
    acknowledgeOrder(orderId);
    navigate('/orders');
  }

  // Back in the app header (`AppLayout`), like the rest of the customer flow.
  useHeaderBackAction(() => navigate('/orders'));

  const showForm = !isLoading && order && order.orderStatus === 'COMPLETED' && !submitted && !alreadyReviewed;
  const showDone = !isLoading && order && order.orderStatus === 'COMPLETED' && (submitted || alreadyReviewed);

  return (
    <div className="focused-page">
      <PageHeader title="השאירו ביקורת" />

      {isLoading && (
        <div className={styles.wrapper}>
          <Skeleton variant="rect" className={styles.summarySkeleton} />
          <Skeleton variant="rect" className={styles.starsSkeleton} />
        </div>
      )}

      {!isLoading && loadError && (
        <div className={styles.banner} role="alert">
          <p>לא הצלחנו לטעון את ההזמנה. אפשר לנסות שוב בעוד רגע.</p>
        </div>
      )}

      {!isLoading && order && order.orderStatus !== 'COMPLETED' && (
        <Card className={styles.notice}>
          <p>ההזמנה הזו עדיין לא הושלמה, לכן אי אפשר להשאיר עליה ביקורת כרגע.</p>
        </Card>
      )}

      {/* §78's "calm success state" — the same `Mascot state="success"` + `listStagger`
          pattern `IssueSuccessStep`/`BookingSuccessStep` established, instead of the single
          line of text in a card this screen used to end on. */}
      {showDone && (
        <div className={styles.doneWrapper}>
          <Mascot state="success" size="xl" />
          <h2 className={styles.doneTitle}>{submitted ? 'תודה על הביקורת' : 'כבר השארת ביקורת'}</h2>
          <p className={styles.doneText}>
            {submitted
              ? 'הביקורת שלך עוזרת ללקוחות הבאים לבחור נכון, ולבעלי המקצוע הטובים לקבל יותר עבודה.'
              : 'כבר קיבלנו ממך ביקורת על ההזמנה הזו, אז אין צורך לדרג שוב.'}
          </p>
          <Button onClick={() => navigate('/orders')} fullWidth>
            חזרה להזמנות שלי
          </Button>
        </div>
      )}

      {showForm && order && (
        <div className={styles.wrapper}>
          <div className={styles.intro}>
            <h2 className={styles.question}>איך היה השירות?</h2>
            <p className={styles.hint}>
              {order.professionalName} · {formatDateLabel(order.bookedStart)}
            </p>
          </div>

          <StarRatingInput value={rating} onChange={setRating} />

          <Textarea
            label="הערות (לא חובה)"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="ספרו לנו עוד על החוויה שלכם…"
            maxLength={REVIEW_COMMENT_MAX_LENGTH}
          />

          {submitError && (
            <div className={styles.banner} role="alert">
              <p>{submitError}</p>
            </div>
          )}

          <div className={styles.formActions}>
            <Button onClick={handleSubmit} loading={isSubmitting} disabled={rating < 1} fullWidth>
              שליחת ביקורת
            </Button>
            {/* Leaving without rating is a first-class outcome, not something reached only by
                backing out of the screen. Acknowledging on the way out is what stops the
                floating prompt from re-offering this same order. */}
            <Button variant="ghost" onClick={handleNotNow} disabled={isSubmitting} fullWidth>
              לא עכשיו
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
