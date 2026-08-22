import { Clock, MapPin, Star } from 'lucide-react';
import { Card } from '../../shared/components';
import type { SosCandidate, SosRequestResponse, SosRequestStatus } from '../../shared/api';
import { formatReviewCount } from '../../shared/utils/hebrewText';
import { SosStatusSteps } from './SosStatusSteps';
import { SosAvatar } from './SosAvatar';
import styles from './SosSelectedProfessionalPanel.module.css';

export interface SosSelectedProfessionalPanelProps {
  request: SosRequestResponse;
  /**
   * The chosen professional's own candidate row, retained from before selection — the candidates
   * endpoint returns nothing once a choice is made, and the customer should not watch the person
   * they just picked disappear. Absent when the screen was opened after the selection already
   * happened; the panel then falls back to `request.selectedProfessionalName`.
   */
  candidate: SosCandidate | null;
}

function price(amount: number): string {
  return `₪${Number.isInteger(amount) ? amount : amount.toFixed(2)}`;
}

/**
 * The label above the professional's name. Follows the job rather than being fixed: it read
 * "בעל המקצוע שבדרך אליך" on a completed visit until the MS2 end-to-end run surfaced it — a
 * present-tense claim about someone who finished and left an hour ago.
 */
function eyebrowFor(status: SosRequestStatus): string {
  switch (status) {
    case 'CONFIRMED':
      return 'בעל המקצוע שיצא אליך';
    case 'ON_THE_WAY':
      return 'בעל המקצוע שבדרך אליך';
    case 'ARRIVED':
      return 'בעל המקצוע שאצלך עכשיו';
    case 'COMPLETED':
      return 'בעל המקצוע שטיפל בתקלה';
    default:
      return 'בעל המקצוע שבחרת';
  }
}

/**
 * Post-selection tracking: who is coming, and where they are up to.
 *
 * Once a professional owns the job the screen stops being about the search — the scan calms down
 * and this becomes the subject. Everything operational after this point (confirm, on the way,
 * arrived, complete) is the professional's action, arriving here as a status change; the customer
 * has nothing to press.
 */
export function SosSelectedProfessionalPanel({ request, candidate }: SosSelectedProfessionalPanelProps) {
  const name = candidate?.fullName ?? request.selectedProfessionalName ?? 'בעל המקצוע שנבחר';
  const area = candidate?.serviceArea ?? candidate?.city ?? null;
  /**
   * The request wins over the retained candidate row, and that ordering is the fix rather than a
   * preference. `candidate` is a snapshot taken before selection and never refreshed — the
   * candidates endpoint returns only `ACCEPTED` offers, so it goes empty the moment a choice is
   * made. Reading the ETA from it meant a professional revising "20 minutes" to "12" on their way
   * over changed nothing on the customer's screen, ever. `selectedEstimatedArrivalMinutes` is
   * re-read from the request on every poll and every realtime-triggered refetch.
   */
  const etaMinutes = request.selectedEstimatedArrivalMinutes ?? candidate?.estimatedArrivalMinutes ?? null;

  return (
    <Card className={styles.panel}>
      <div className={styles.top}>
        <SosAvatar
          imageUrl={candidate?.profileImageUrl ?? null}
          fullName={name}
          imageClassName={styles.avatar}
          fallbackClassName={styles.avatarFallback}
        />
        <div className={styles.identity}>
          <p className={styles.eyebrow}>{eyebrowFor(request.status)}</p>
          <h2 className={styles.name}>{name}</h2>
          {candidate?.averageRating != null && (
            <span className={styles.rating}>
              <Star size={14} className={styles.ratingStar} aria-hidden="true" fill="currentColor" />
              {candidate.averageRating.toFixed(1)}
              <span className={styles.reviewCount}>· {formatReviewCount(candidate.reviewCount)}</span>
            </span>
          )}
        </div>
      </div>

      <SosStatusSteps status={request.status} />

      <div className={styles.facts}>
        {etaMinutes != null && (
          <p className={styles.fact}>
            <Clock size={15} aria-hidden="true" />
            {/* The professional's own committed ETA, not a live position — GPS tracking is a later
                milestone and this must not imply it exists. */}
            זמן הגעה משוער: כ־{etaMinutes} דקות
          </p>
        )}
        {area && (
          <p className={styles.fact}>
            <MapPin size={15} aria-hidden="true" />
            {area}
          </p>
        )}
      </div>

      {candidate && (
        <div className={styles.priceRow}>
          <span className={styles.priceLabel}>סה״כ לביקור</span>
          <span className={styles.priceValue}>{price(candidate.totalVisitCost)}</span>
        </div>
      )}
    </Card>
  );
}
