import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Star } from 'lucide-react';
import { PageHeader, Card, Button, Textarea } from '../../shared/components';
import { useActiveOrder } from '../../shared/hooks';
import { getOrder, createReview, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { OrderDetailResponse } from '../../shared/api';
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
  const [hoverRating, setHoverRating] = useState(0);
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

  const showForm = !isLoading && order && order.orderStatus === 'COMPLETED' && !submitted && !alreadyReviewed;
  const showDone = !isLoading && order && order.orderStatus === 'COMPLETED' && (submitted || alreadyReviewed);

  return (
    <div className="focused-page">
      <PageHeader title="השאירו ביקורת" onBack={() => navigate('/orders')} />

      {isLoading && <p>טוען…</p>}

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

      {showDone && (
        <Card className={styles.notice}>
          <p>{submitted ? 'תודה! הביקורת שלכם נשלחה בהצלחה.' : 'כבר השארתם ביקורת על ההזמנה הזו.'}</p>
          <Button onClick={() => navigate('/orders')}>חזרה להזמנות שלי</Button>
        </Card>
      )}

      {showForm && order && (
        <div className={styles.wrapper}>
          <Card className={styles.summaryCard}>
            <p className={styles.professionalName}>{order.professionalName}</p>
            <p className={styles.hint}>איך היה השירות?</p>
          </Card>

          <div className={styles.stars} role="radiogroup" aria-label="דירוג">
            {[1, 2, 3, 4, 5].map((value) => (
              <button
                key={value}
                type="button"
                className={styles.starButton}
                style={{ color: (hoverRating || rating) >= value ? 'var(--color-warning)' : undefined }}
                aria-label={`${value} כוכבים`}
                aria-pressed={rating === value}
                onMouseEnter={() => setHoverRating(value)}
                onMouseLeave={() => setHoverRating(0)}
                onClick={() => setRating(value)}
              >
                <Star
                  size={32}
                  className={styles.star}
                  fill={(hoverRating || rating) >= value ? 'currentColor' : 'none'}
                  aria-hidden="true"
                />
              </button>
            ))}
          </div>

          <Textarea
            label="הערות (לא חובה)"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="ספרו לנו עוד על החוויה שלכם…"
          />

          {submitError && (
            <div className={styles.banner} role="alert">
              <p>{submitError}</p>
            </div>
          )}

          <Button onClick={handleSubmit} loading={isSubmitting} disabled={rating < 1} fullWidth>
            שליחת ביקורת
          </Button>
        </div>
      )}
    </div>
  );
}
