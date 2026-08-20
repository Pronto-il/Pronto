import { MessageSquare, Star } from 'lucide-react';
import { EmptyState } from '../../shared/components';
import type { ReviewResponse } from '../../shared/api';
import { formatRelativeAgeLabel } from '../../shared/utils/formatDateTime';
import styles from './ProfessionalProfilePage.module.css';

export interface ReviewListProps {
  reviews: ReviewResponse[];
  isLoading: boolean;
  /** A failed review fetch doesn't block the rest of the profile page — surfaced inline here only. */
  error: string | null;
}

/**
 * DESIGN_SYSTEM.md §45's review-card format: `customerName`, a 5-star rating (filled count =
 * `rating` — distinct from the numeric "★ 4.9 · 127" aggregate format §31 already
 * established for cards/headers, both correct in their own place), a relative age label, and
 * `comment` when present. Co-located in `features/professionals/` (this page's only
 * consumer) rather than its own `features/reviews/` module — see `frontend-ms8-design.md`
 * §5. Reuses `ProfessionalProfilePage.module.css` (its only consumer) rather than a
 * dedicated stylesheet.
 */
export function ReviewList({ reviews, isLoading, error }: ReviewListProps) {
  if (isLoading) {
    return (
      <div className={styles.reviewsSkeleton}>
        <div className={styles.skeletonLine} />
        <div className={styles.skeletonLine} />
      </div>
    );
  }

  if (error) {
    return (
      <div className={styles.banner} role="alert">
        <p>{error}</p>
      </div>
    );
  }

  if (reviews.length === 0) {
    return (
      <EmptyState
        icon={<MessageSquare size={40} strokeWidth={1.5} aria-hidden="true" />}
        title="עדיין אין ביקורות"
        description="בעל המקצוע הזה עדיין לא קיבל ביקורות מלקוחות בפרונטו."
      />
    );
  }

  return (
    <div className={styles.reviewList}>
      {reviews.map((review) => (
        <div key={review.id} className={styles.reviewCard}>
          <div className={styles.reviewHeader}>
            <span className={styles.reviewerName}>{review.customerName ?? 'לקוח/ה'}</span>
            <span className={styles.reviewAge}>{formatRelativeAgeLabel(review.createdAt)}</span>
          </div>
          <div className={styles.reviewStars} aria-label={`${review.rating} מתוך 5 כוכבים`}>
            {[1, 2, 3, 4, 5].map((value) => (
              <Star
                key={value}
                size={16}
                className={styles.reviewStar}
                fill={value <= review.rating ? 'currentColor' : 'none'}
                aria-hidden="true"
              />
            ))}
          </div>
          {review.comment && <p className={styles.reviewComment}>{review.comment}</p>}
        </div>
      ))}
    </div>
  );
}
