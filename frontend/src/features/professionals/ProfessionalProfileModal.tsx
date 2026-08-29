import { useEffect, useState } from 'react';
import { Modal, Skeleton } from '../../shared/components';
import { getProfessionalProfile, getReviews, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { ProfessionalProfileResponse, PublicReviewResponse } from '../../shared/api';
import { ProfessionalProfileDisplay } from './ProfessionalProfileDisplay';
import { ReviewList } from './ReviewList';
import styles from './ProfessionalProfileModal.module.css';

export interface ProfessionalProfileModalProps {
  /** `null` while nothing is selected — the modal stays mounted and simply doesn't open. */
  professionalId: number | null;
  isOpen: boolean;
  onClose: () => void;
}

/**
 * The professional's profile shown **in place**, without leaving the screen the customer is on —
 * the shared `Modal` (bottom sheet on mobile, centred dialog on desktop, §57-59) wrapped around
 * the very same `ProfessionalProfileDisplay` + `ReviewList` pair `/professionals/:id` renders.
 * Identical information, identical components, no second source of truth: this is a presentation
 * of the existing profile page's content, not a reduced copy of it.
 *
 * Deliberately view-only. The favourite toggle and the "select this professional" CTA are
 * flow-specific actions that stay on the real page — a modal opened from a past order has no
 * booking flow to resume into.
 *
 * Fetches lazily, the first time it is actually opened for a given professional, so a list that
 * renders many of these costs nothing until one is tapped.
 */
export function ProfessionalProfileModal({ professionalId, isOpen, onClose }: ProfessionalProfileModalProps) {
  const [professional, setProfessional] = useState<ProfessionalProfileResponse | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [notFound, setNotFound] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [reviews, setReviews] = useState<PublicReviewResponse[]>([]);
  const [isLoadingReviews, setIsLoadingReviews] = useState(false);
  const [reviewsError, setReviewsError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen || professionalId === null) {
      return;
    }
    let cancelled = false;
    setProfessional(null);
    setNotFound(false);
    setLoadError(null);
    setIsLoading(true);
    getProfessionalProfile(professionalId)
      .then((result) => {
        if (!cancelled) setProfessional(result);
      })
      .catch((error) => {
        if (cancelled) return;
        if (error instanceof ApiError && error.status === 404) {
          setNotFound(true);
        } else {
          setLoadError(GENERIC_ERROR_MESSAGE);
        }
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    // Independent of the profile fetch, exactly as the full page does it — a slow or failed
    // review list never holds up the identity block.
    setReviews([]);
    setReviewsError(null);
    setIsLoadingReviews(true);
    getReviews(professionalId)
      .then((result) => {
        if (!cancelled) setReviews(result.reviews);
      })
      .catch(() => {
        if (!cancelled) setReviewsError(GENERIC_ERROR_MESSAGE);
      })
      .finally(() => {
        if (!cancelled) setIsLoadingReviews(false);
      });

    return () => {
      cancelled = true;
    };
  }, [isOpen, professionalId]);

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="פרופיל בעל מקצוע" size="normal">
      {isLoading && (
        <div className={styles.loading}>
          <Skeleton variant="circle" className={styles.loadingAvatar} />
          <Skeleton variant="text" lines={3} />
        </div>
      )}

      {!isLoading && notFound && <p className={styles.notice}>לא מצאנו את בעל המקצוע המבוקש.</p>}

      {!isLoading && loadError && (
        <p className={styles.notice} role="alert">
          {loadError}
        </p>
      )}

      {!isLoading && professional && (
        <>
          <ProfessionalProfileDisplay professional={professional} />
          <section className={styles.reviewsSection}>
            <h3 className={styles.sectionTitle}>ביקורות</h3>
            <ReviewList reviews={reviews} isLoading={isLoadingReviews} error={reviewsError} />
          </section>
        </>
      )}
    </Modal>
  );
}
