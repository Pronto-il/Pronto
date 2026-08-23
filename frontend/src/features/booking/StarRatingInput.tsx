import { useState } from 'react';
import { Star } from 'lucide-react';
import styles from './StarRatingInput.module.css';

/** Doubles as each star's accessible name and as the visible label under the row — a bare
 *  "3 כוכבים" says nothing about what three stars means. */
const RATING_LABELS: Record<number, string> = {
  1: 'לא טוב',
  2: 'בסדר',
  3: 'טוב',
  4: 'טוב מאוד',
  5: 'מצוין',
};

export interface StarRatingInputProps {
  /** `0` = nothing chosen yet. */
  value: number;
  onChange: (rating: number) => void;
  /** Star glyph size — `36` on the full review page, smaller inside the prompt modal. */
  size?: number;
}

/**
 * The 1-5 star picker, extracted verbatim from `CompletionReviewPage` when the review-prompt
 * modal became a second place a customer can rate a visit from. One control, one set of labels,
 * one hover/selection behaviour — the two surfaces differ in framing, never in how rating works.
 */
export function StarRatingInput({ value, onChange, size = 36 }: StarRatingInputProps) {
  const [hoverRating, setHoverRating] = useState(0);
  const effective = hoverRating || value;

  return (
    <div className={styles.ratingBlock}>
      <div className={styles.stars} role="radiogroup" aria-label="דירוג">
        {[1, 2, 3, 4, 5].map((star) => (
          <button
            key={star}
            type="button"
            className={styles.starButton}
            style={{ color: effective >= star ? 'var(--color-warning)' : undefined }}
            aria-label={RATING_LABELS[star]}
            aria-pressed={value === star}
            onMouseEnter={() => setHoverRating(star)}
            onMouseLeave={() => setHoverRating(0)}
            onClick={() => onChange(star)}
          >
            <Star
              size={size}
              className={styles.star}
              fill={effective >= star ? 'currentColor' : 'none'}
              aria-hidden="true"
            />
          </button>
        ))}
      </div>
      {/* Names the scale as it's used, so five identical stars aren't the only feedback the
          customer gets for what they just picked. Reserves its own line either way, so choosing a
          rating doesn't shift the form below it. */}
      <p className={styles.ratingLabel}>{RATING_LABELS[effective] ?? ' '}</p>
    </div>
  );
}
