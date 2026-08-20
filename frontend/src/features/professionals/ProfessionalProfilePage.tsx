import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { Heart } from 'lucide-react';
import { PageHeader, Card, Button, Skeleton } from '../../shared/components';
import { useAuth, useBookingDraft } from '../../shared/hooks';
import {
  getProfessionalProfile,
  getReviews,
  addFavorite,
  removeFavorite,
  ApiError,
  GENERIC_ERROR_MESSAGE,
} from '../../shared/api';
import type { ProfessionalProfileResponse, ReviewResponse } from '../../shared/api';
import { ReviewList } from './ReviewList';
import { ProfessionalProfileDisplay } from './ProfessionalProfileDisplay';
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
 *
 * **MS6 Professional Command Center**: the identity/info/bio block is now
 * `ProfessionalProfileDisplay` (design doc §7.1), extracted so `ProfileEditorPage.tsx` can
 * reuse it for a live unsaved-edits preview. The favorite button/error, reviews section, and
 * select-CTA stay inline here (live-page-only concerns) — zero change to this page's own
 * data-fetching, favorite-toggle, or select-CTA logic.
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

      {isLoading && (
        <div className={styles.loadingIdentity}>
          <Skeleton variant="circle" className={styles.loadingAvatar} />
          <Skeleton variant="text" lines={2} className={styles.loadingLines} />
        </div>
      )}

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
          <ProfessionalProfileDisplay professional={professional} />

          {user?.role === 'CUSTOMER' && (
            <div className={styles.favoriteButtonWrapper}>
              <button
                type="button"
                className={`${styles.favoriteButton} ${isFavorited ? styles.favoriteButtonActive : ''}`}
                onClick={handleToggleFavorite}
                disabled={isTogglingFavorite}
                aria-pressed={isFavorited}
              >
                <Heart size={18} aria-hidden="true" fill={isFavorited ? 'currentColor' : 'none'} />
                {/* Was "הוסר ממועדפים" — past-tense passive ("was removed"), which reads as a
                    status message rather than the action the button performs. */}
                <span>{isFavorited ? 'הסרה ממועדפים' : 'הוספה למועדפים'}</span>
              </button>
              {favoriteError && (
                <p className={styles.favoriteError} role="alert">
                  {favoriteError}
                </p>
              )}
            </div>
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
