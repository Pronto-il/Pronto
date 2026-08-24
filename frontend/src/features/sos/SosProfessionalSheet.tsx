import { useEffect, useState } from 'react';
import { Clock, MapPin, ShieldCheck, Star } from 'lucide-react';
import { Button, Modal, Skeleton } from '../../shared/components';
import { getProfessionalProfile, getReviews } from '../../shared/api';
import type { ProfessionalProfileResponse, ReviewResponse, SosCandidate } from '../../shared/api';
import { formatReviewCount } from '../../shared/utils/hebrewText';
import { SosAvatar } from './SosAvatar';
import styles from './SosProfessionalSheet.module.css';

export interface SosProfessionalSheetProps {
  /**
   * The candidate to show, or `null` when closed. **Always the canonical row** from the live
   * candidates list, never a copy taken at open time — so an ETA revision arriving while the sheet
   * is up re-renders it, and a candidate that stops being valid takes the sheet down with it.
   */
  candidate: SosCandidate | null;
  /** The backend's authority on whether `/select` would be accepted right now. */
  selectionOpen: boolean;
  /** Shown under a disabled CTA. Identical to the main candidate UI's explanation, by construction. */
  selectionHint: string;
  isSubmitting: boolean;
  isPending: boolean;
  onSelect: (candidate: SosCandidate) => void;
  onClose: () => void;
}

/** How many reviews to render. The sheet is a decision aid, not the full profile page. */
const MAX_REVIEWS = 3;

function price(amount: number): string {
  return `₪${Number.isInteger(amount) ? amount : amount.toFixed(2)}`;
}

function firstName(fullName: string | null): string {
  return fullName ? fullName.trim().split(/\s+/)[0] : 'בעל המקצוע';
}

/**
 * **The in-place details surface.** A bottom sheet on mobile, a centred dialog on desktop — both
 * from the shared `Modal`, which already switches presentation at the 640px breakpoint, so this
 * follows the app's existing convention rather than inventing a second sheet.
 *
 * ## The constraint that shapes everything here
 *
 * The live SOS screen **stays mounted underneath**. This is not a route, and opening it must not
 * unmount anything: the socket stays subscribed, the selection countdown keeps counting, new
 * professionals keep arriving on the scan surface behind, and closing returns to exactly the state
 * the customer left. Navigating to `/professionals/:id` would have been far less code and would
 * have thrown all of that away mid-emergency.
 *
 * Because the screen is still live, so is this sheet: `candidate` is the canonical row from
 * `useSosRequest`'s candidates list, so a professional revising their ETA updates the figure here
 * without a close/reopen, and one who expires or is no longer selectable causes the parent to
 * close the sheet rather than leaving a stale surface offering a button that cannot work.
 *
 * ## Profile data
 *
 * The SOS candidate DTO deliberately carries only what a candidate card needs. Bio, service area
 * and review text come from the existing `GET /api/professionals/{id}` and `GET /api/reviews`,
 * fetched when the sheet opens — no SOS-side duplication of the profile model, per the brief.
 *
 * **That fetch is decoration, and is treated as such.** If it fails the sheet keeps working: the
 * candidate's own fields (name, photo, rating, ETA, the full price breakdown) all come from SOS
 * state that is already loaded, and the select CTA stays exactly as usable as the canonical state
 * says it is. A profile endpoint having a bad minute must not be able to block an emergency
 * booking.
 */
export function SosProfessionalSheet({
  candidate,
  selectionOpen,
  selectionHint,
  isSubmitting,
  isPending,
  onSelect,
  onClose,
}: SosProfessionalSheetProps) {
  const [profile, setProfile] = useState<ProfessionalProfileResponse | null>(null);
  const [reviews, setReviews] = useState<ReviewResponse[]>([]);
  const [isLoadingProfile, setIsLoadingProfile] = useState(false);

  const professionalId = candidate?.professionalId ?? null;

  // Keyed on the professional, not on `candidate`: the candidate object gets a new identity on
  // every poll tick, and re-fetching the profile every 3s would be pointless load on an endpoint
  // whose content does not change during a two-minute window.
  useEffect(() => {
    if (professionalId === null) {
      return;
    }
    let cancelled = false;
    setIsLoadingProfile(true);
    setProfile(null);
    setReviews([]);

    Promise.allSettled([getProfessionalProfile(professionalId), getReviews(professionalId)])
      .then(([profileResult, reviewsResult]) => {
        if (cancelled) {
          return;
        }
        // Settled, not `all`: reviews failing must not also cost the customer the bio, and either
        // failing must not cost them the sheet. Whatever arrived is rendered; whatever did not is
        // simply absent, and the sections below are each conditional on their own data.
        if (profileResult.status === 'fulfilled') {
          setProfile(profileResult.value);
        }
        if (reviewsResult.status === 'fulfilled') {
          setReviews(reviewsResult.value.reviews.filter((review) => review.comment).slice(0, MAX_REVIEWS));
        }
        setIsLoadingProfile(false);
      });

    return () => {
      cancelled = true;
      setIsLoadingProfile(false);
    };
  }, [professionalId]);

  if (!candidate) {
    return null;
  }

  const name = candidate.fullName ?? 'בעל מקצוע';
  const area = profile?.serviceRegionNameHe ?? candidate.serviceRegion ?? candidate.city;

  return (
    <Modal
      isOpen
      onClose={onClose}
      title="פרטי בעל המקצוע"
      size="normal"
      footer={
        <div className={styles.footer}>
          <Button
            onClick={() => onSelect(candidate)}
            disabled={!selectionOpen || isSubmitting}
            loading={isPending}
            fullWidth
          >
            בחר את {firstName(candidate.fullName)}
          </Button>
          {/* Same explanation the main candidate UI gives, for the same reason: a CTA that is
              disabled without saying why reads as broken, and one that is enabled into a
              guaranteed SOS_INVALID_STATE is a worse lie than a disabled one. */}
          {!selectionOpen && <p className={styles.footerHint}>{selectionHint}</p>}
        </div>
      }
    >
      <div className={styles.body}>
        <div className={styles.identity}>
          <SosAvatar
            imageUrl={candidate.profileImageUrl}
            fullName={candidate.fullName}
            imageClassName={styles.avatar}
            fallbackClassName={styles.avatarFallback}
            enlargeable
          />
          <div className={styles.identityText}>
            <h3 className={styles.name}>{name}</h3>
            {candidate.averageRating !== null ? (
              <span className={styles.rating}>
                <Star size={14} fill="currentColor" aria-hidden="true" />
                {candidate.averageRating.toFixed(1)}
                <span className={styles.reviewCount}>· {formatReviewCount(candidate.reviewCount)}</span>
              </span>
            ) : (
              <span className={styles.noRating}>עדיין אין ביקורות</span>
            )}
          </div>
        </div>

        {/* The two facts this decision actually turns on, given equal weight. */}
        <div className={styles.highlights}>
          {candidate.estimatedArrivalMinutes !== null && (
            <div className={styles.highlight}>
              <Clock size={16} aria-hidden="true" />
              <span className={styles.highlightLabel}>זמן הגעה</span>
              <span className={styles.highlightValue} aria-live="polite">
                כ־{candidate.estimatedArrivalMinutes} דק׳
              </span>
            </div>
          )}
          {area && (
            <div className={styles.highlight}>
              <MapPin size={16} aria-hidden="true" />
              <span className={styles.highlightLabel}>אזור שירות</span>
              <span className={styles.highlightValue}>{area}</span>
            </div>
          )}
        </div>

        {/* §13: the urgency surcharge is disclosed, never folded into one number. Straight from the
            candidate row, so it is present even when the profile fetch failed. */}
        <div className={styles.prices}>
          {candidate.visitFee !== null && (
            <div className={styles.priceRow}>
              <span>דמי ביקור</span>
              <span>{price(candidate.visitFee)}</span>
            </div>
          )}
          <div className={styles.priceRow}>
            <span>תוספת קריאה דחופה</span>
            <span>{price(candidate.sosFee)}</span>
          </div>
          <div className={`${styles.priceRow} ${styles.priceTotal}`}>
            <span>סה״כ לביקור</span>
            <span>{price(candidate.totalVisitCost)}</span>
          </div>
          <p className={styles.priceNote}>
            המחיר הוא עבור הביקור. עלות התיקון עצמו נסגרת מולך במקום.
          </p>
        </div>

        {isLoadingProfile ? (
          <div className={styles.loading}>
            <Skeleton variant="text" />
            <Skeleton variant="text" />
            <Skeleton variant="text" />
          </div>
        ) : (
          <>
            {profile?.approvalStatus === 'APPROVED' && (
              <p className={styles.trust}>
                <ShieldCheck size={15} aria-hidden="true" />
                בעל מקצוע מאומת בפרונטו
              </p>
            )}

            {profile?.bio && (
              <section className={styles.section}>
                <h4 className={styles.sectionTitle}>על בעל המקצוע</h4>
                <p className={styles.bio}>{profile.bio}</p>
              </section>
            )}

            {reviews.length > 0 && (
              <section className={styles.section}>
                <h4 className={styles.sectionTitle}>מה לקוחות סיפרו</h4>
                <ul className={styles.reviews}>
                  {reviews.map((review) => (
                    <li key={review.id} className={styles.review}>
                      <span className={styles.reviewHead}>
                        <span className={styles.reviewRating}>
                          <Star size={12} fill="currentColor" aria-hidden="true" />
                          {review.rating}
                        </span>
                        {review.customerName && (
                          <span className={styles.reviewAuthor}>{review.customerName}</span>
                        )}
                      </span>
                      <p className={styles.reviewComment}>{review.comment}</p>
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </>
        )}
      </div>
    </Modal>
  );
}
