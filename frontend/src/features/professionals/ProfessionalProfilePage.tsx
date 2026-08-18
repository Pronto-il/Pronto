import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { Heart, Star } from 'lucide-react';
import { PageHeader, Card, Button } from '../../shared/components';
import { useAuth, useBookingDraft } from '../../shared/hooks';
import {
  getProfessionalProfile,
  getReviews,
  addFavorite,
  removeFavorite,
  ApiError,
  GENERIC_ERROR_MESSAGE,
  getCategoryNameHe,
} from '../../shared/api';
import type { ProfessionalProfileResponse, ReviewResponse } from '../../shared/api';
import { ReviewList } from './ReviewList';
import type { ProfessionalDetailLocationState } from './ProfessionalCard';
import styles from './ProfessionalProfilePage.module.css';

/**
 * `/professionals/:professionalId` — bare `RequireAuth` (no role param), matching the
 * backend's either-role, no-route-gate `GET /api/professionals/{id}` (`frontend-ms8-design.md`
 * §3). Fetches the profile and the review list independently in parallel — a slow/failed
 * review fetch never blocks the rest of the page (`ReviewList` owns its own loading/error
 * state).
 *
 * The "select professional" CTA only renders when `location.state` carries
 * `fromIssueId`/`urgencyType` (i.e. reached via a `ProfessionalCard`'s identity-block link
 * from an active booking flow, §2.3) — a direct visit, a page refresh, or arriving via
 * `/favorites` (which passes no state) all correctly degrade to a view-only page. Clicking
 * it writes into each flow's own pre-existing draft/resume mechanism (unmodified) and
 * navigates back into that flow; it never re-implements booking/SOS selection itself.
 */
export default function ProfessionalProfilePage() {
  const { professionalId: professionalIdParam } = useParams<{ professionalId: string }>();
  const professionalId = Number(professionalIdParam);
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useAuth();
  const { updateDraft } = useBookingDraft();

  const locationState = location.state as ProfessionalDetailLocationState | null;
  const hasSelectContext = Boolean(locationState?.fromIssueId && locationState?.urgencyType);

  const [professional, setProfessional] = useState<ProfessionalProfileResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [reviews, setReviews] = useState<ReviewResponse[]>([]);
  const [isLoadingReviews, setIsLoadingReviews] = useState(true);
  const [reviewsError, setReviewsError] = useState<string | null>(null);

  const [isFavorited, setIsFavorited] = useState(false);
  const [isTogglingFavorite, setIsTogglingFavorite] = useState(false);
  const [favoriteError, setFavoriteError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setNotFound(false);
    setLoadError(null);
    getProfessionalProfile(professionalId)
      .then((result) => {
        if (cancelled) return;
        setProfessional(result);
        setIsFavorited(result.favorited ?? false);
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
    return () => {
      cancelled = true;
    };
  }, [professionalId]);

  useEffect(() => {
    let cancelled = false;
    setIsLoadingReviews(true);
    setReviewsError(null);
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
  }, [professionalId]);

  async function handleToggleFavorite() {
    const next = !isFavorited;
    setIsFavorited(next);
    setFavoriteError(null);
    setIsTogglingFavorite(true);
    try {
      if (next) {
        await addFavorite(professionalId);
      } else {
        await removeFavorite(professionalId);
      }
    } catch {
      setIsFavorited(!next);
      setFavoriteError(GENERIC_ERROR_MESSAGE);
    } finally {
      setIsTogglingFavorite(false);
    }
  }

  function handleSelectProfessional() {
    if (!locationState) return;
    if (locationState.urgencyType === 'STANDARD') {
      updateDraft({ stage: 'SLOT_SELECTION', professionalId });
      navigate(`/issues/${locationState.fromIssueId}/booking`);
    } else {
      updateDraft({ stage: 'BOOKING_CONFIRM', professionalId });
      navigate(`/issues/${locationState.fromIssueId}/sos-booking`);
    }
  }

  return (
    <div className="focused-page">
      <PageHeader title="פרופיל בעל מקצוע" onBack={() => navigate(-1)} />

      {isLoading && <p>טוען…</p>}

      {!isLoading && notFound && (
        <Card className={styles.notice}>
          <p>לא מצאנו את בעל המקצוע המבוקש.</p>
        </Card>
      )}

      {!isLoading && loadError && (
        <div className={styles.banner} role="alert">
          <p>{loadError}</p>
        </div>
      )}

      {!isLoading && professional && (
        <>
          <div className={styles.identityBlock}>
            {professional.profileImageUrl ? (
              <img src={professional.profileImageUrl} alt="" className={styles.photo} />
            ) : (
              <span className={styles.photoFallback} aria-hidden="true">
                {professional.fullName.trim().charAt(0)}
              </span>
            )}
            <h1 className={styles.name}>{professional.fullName}</h1>
            <p className={styles.category}>{getCategoryNameHe(professional.categoryId)}</p>
            {professional.averageRating !== null && (
              <span className={styles.rating}>
                <Star size={16} className={styles.ratingStar} aria-hidden="true" fill="currentColor" />
                {professional.averageRating.toFixed(1)}
                <span className={styles.reviewCount}>· {professional.reviewCount} ביקורות</span>
              </span>
            )}

            {user?.role === 'CUSTOMER' && (
              <button
                type="button"
                className={`${styles.favoriteButton} ${isFavorited ? styles.favoriteButtonActive : ''}`}
                onClick={handleToggleFavorite}
                disabled={isTogglingFavorite}
                aria-pressed={isFavorited}
              >
                <Heart size={18} aria-hidden="true" fill={isFavorited ? 'currentColor' : 'none'} />
                <span>{isFavorited ? 'הוסר ממועדפים' : 'הוספה למועדפים'}</span>
              </button>
            )}
            {favoriteError && (
              <p className={styles.favoriteError} role="alert">
                {favoriteError}
              </p>
            )}
          </div>

          <Card className={styles.infoCard}>
            <div className={styles.row}>
              <span className={styles.rowLabel}>אזור שירות</span>
              <span className={styles.rowValue}>{professional.serviceArea}</span>
            </div>
            {professional.city && (
              <div className={styles.row}>
                <span className={styles.rowLabel}>עיר</span>
                <span className={styles.rowValue}>{professional.city}</span>
              </div>
            )}
            <div className={styles.row}>
              <span className={styles.rowLabel}>מחיר ביקור</span>
              <span className={styles.rowValue}>₪{professional.basePrice}</span>
            </div>
          </Card>

          {professional.bio && (
            <Card className={styles.infoCard}>
              <p className={styles.bioTitle}>קצת עליי</p>
              <p className={styles.bioText}>{professional.bio}</p>
            </Card>
          )}

          <section className={styles.reviewsSection}>
            <h2 className={styles.sectionTitle}>ביקורות</h2>
            <ReviewList reviews={reviews} isLoading={isLoadingReviews} error={reviewsError} />
          </section>

          {hasSelectContext && (
            <div className={styles.ctaBar}>
              <Button onClick={handleSelectProfessional} fullWidth>
                בחירת בעל מקצוע
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
