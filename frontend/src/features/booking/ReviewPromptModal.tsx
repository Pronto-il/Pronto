import { useState } from 'react';
import { Button, Modal, Textarea } from '../../shared/components';
import { createReview, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import styles from './ReviewPromptModal.module.css';
import { StarRatingInput } from './StarRatingInput';

const SUBMIT_ERROR_MESSAGES: Record<string, string> = {
  REVIEW_ORDER_NOT_COMPLETED: 'לא ניתן להשאיר ביקורת על הזמנה שטרם הושלמה.',
};

export interface ReviewPromptModalProps {
  isOpen: boolean;
  orderId: number;
  /** Called for every way out of this dialog — submitted, dismissed, closed, or already-reviewed.
   *  The caller is expected to acknowledge the order so the prompt does not return. */
  onDismiss: () => void;
}

/**
 * The review prompt itself: the shared `Modal` (bottom sheet on mobile, centred dialog on
 * desktop) over whatever screen the customer is on, opened by the floating indicator once their
 * most recent visit is complete and unrated.
 *
 * Two equally real ways out, which is the point of the change — "שליחת ביקורת" and a plainly
 * visible "לא עכשיו". Either one dismisses the prompt for good via the caller's
 * `acknowledgeOrder`, so nothing is nagged twice and a review is never a toll gate on the rest of
 * the app. The full `/orders/:id/review` screen is untouched and still reachable (from order
 * tracking, and from the SOS completion screen) for a customer who wants to come back to it.
 *
 * `REVIEW_ALREADY_EXISTS` is treated as success rather than an error — the customer's intent is
 * satisfied, the order simply already had a rating (e.g. left on another device).
 */
export function ReviewPromptModal({ isOpen, orderId, onDismiss }: ReviewPromptModalProps) {
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  async function handleSubmit() {
    if (rating < 1) {
      return;
    }
    setSubmitError(null);
    setIsSubmitting(true);
    try {
      await createReview({ orderId, rating, comment: comment.trim() || undefined });
      onDismiss();
    } catch (error) {
      if (error instanceof ApiError && error.code === 'REVIEW_ALREADY_EXISTS') {
        onDismiss();
      } else if (error instanceof ApiError && SUBMIT_ERROR_MESSAGES[error.code]) {
        setSubmitError(SUBMIT_ERROR_MESSAGES[error.code]);
      } else {
        setSubmitError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <Modal
      isOpen={isOpen}
      onClose={onDismiss}
      title="איך היה השירות?"
      size="small"
      footer={
        <div className={styles.actions}>
          <Button onClick={handleSubmit} loading={isSubmitting} disabled={rating < 1} fullWidth>
            שליחת ביקורת
          </Button>
          <Button variant="ghost" onClick={onDismiss} disabled={isSubmitting} fullWidth>
            לא עכשיו
          </Button>
        </div>
      }
    >
      <div className={styles.body}>
        <p className={styles.intro}>הדירוג שלך עוזר ללקוחות הבאים לבחור נכון. אפשר גם לדלג.</p>

        <StarRatingInput value={rating} onChange={setRating} size={32} />

        <Textarea
          label="הערות (לא חובה)"
          value={comment}
          onChange={(event) => setComment(event.target.value)}
          placeholder="ספרו לנו עוד על החוויה שלכם…"
        />

        {submitError && (
          <div className={styles.banner} role="alert">
            <p>{submitError}</p>
          </div>
        )}
      </div>
    </Modal>
  );
}
