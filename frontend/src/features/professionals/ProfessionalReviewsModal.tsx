import { useEffect, useState } from 'react';
import { Modal } from '../../shared/components';
import { getReviews, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { ReviewResponse } from '../../shared/api';
import { ReviewList } from './ReviewList';

export interface ProfessionalReviewsModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** Whose reviews to show — `GET /api/reviews?professionalId=`, either role, unchanged. */
  professionalId: number;
}

/**
 * Every review written for one professional, in a modal (`Modal`'s body is already the
 * scrollable region, so a long history scrolls inside the sheet/dialog rather than the page).
 *
 * Reuses the existing reviews API and `ReviewList` verbatim — the same customer name, star
 * rating, relative date and comment the public profile page renders. It exists so the review
 * count on a professional's own profile can open the reviews in place instead of sending them
 * to another screen.
 */
export function ProfessionalReviewsModal({ isOpen, onClose, professionalId }: ProfessionalReviewsModalProps) {
  const [reviews, setReviews] = useState<ReviewResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    let cancelled = false;
    setIsLoading(true);
    setError(null);
    getReviews(professionalId)
      .then((result) => {
        if (!cancelled) {
          setReviews(result.reviews);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setError(GENERIC_ERROR_MESSAGE);
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
  }, [isOpen, professionalId]);

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="ביקורות" size="normal">
      <ReviewList reviews={reviews} isLoading={isLoading} error={error} />
    </Modal>
  );
}
