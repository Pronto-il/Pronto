import { Clock, MapPin, Star } from 'lucide-react';
import { Button, Card } from '../../shared/components';
import type { SosCandidate } from '../../shared/api';
import { formatReviewCount } from '../../shared/utils/hebrewText';
import { SosAvatar } from './SosAvatar';
import styles from './SosCandidateCard.module.css';

export interface SosCandidateCardProps {
  candidate: SosCandidate;
  /**
   * The backend's authority on whether `/select` is accepted right now. When false the CTA is
   * disabled rather than hidden — the customer can already read and compare, they just cannot
   * commit yet, and hiding the button would make the card look inert.
   */
  selectionOpen: boolean;
  /** A selection is in flight (this candidate or another) — blocks double submits across the tray. */
  isSubmitting: boolean;
  /** True for the candidate whose select button was pressed; carries the spinner. */
  isPending: boolean;
  onSelect: (candidate: SosCandidate) => void;
  /** Opens the details surface. Never selects — inspecting and committing stay separate actions. */
  onOpenDetails: (candidate: SosCandidate) => void;
}

/** ₪ with no decimals when the amount is whole — the app's existing price formatting. */
function price(amount: number): string {
  return `₪${Number.isInteger(amount) ? amount : amount.toFixed(2)}`;
}

/**
 * One professional on the customer's SOS screen, in one of **two clearly different states**.
 *
 * `REQUESTED` — the platform has contacted them and they have not answered. Rendered muted: no
 * arrival time (nobody has promised one), no select button, and a plain "ממתין לתשובה". The point
 * is that the customer can see the search is real and who is in it, without being misled into
 * thinking anyone has committed.
 *
 * `ACCEPTED` — they answered and named an arrival time. Full contrast, a clear badge, the ETA, and
 * a live CTA. Even then it means **"I am available and can come"** and nothing more: the card is an
 * option, not an assignment, and the job is awarded only when the customer presses `בחר`.
 *
 * <p>Rejected and expired professionals never reach this component — the backend drops them from
 * the list. There is deliberately no "declined" rendering: a customer in an emergency needs to know
 * who might still come, not who will not, and one professional's decision to decline is not a
 * stranger's business.
 *
 * Structure mirrors `features/professionals`' `ProfessionalCard` (photo, name, rating with an
 * honest "no reviews yet", a meta strip of comparison signals, a price block and one primary CTA)
 * so the two read as the same product. What is genuinely different is the price: an SOS visit is
 * a visit fee **plus** a disclosed urgency surcharge, and FRONTEND_AGENT.md §13 requires that
 * surcharge to be visible rather than folded into one number — so the breakdown is itemized above
 * the total instead of reusing the listing card's single-price block.
 */
export function SosCandidateCard({
  candidate,
  selectionOpen,
  isSubmitting,
  isPending,
  onSelect,
  onOpenDetails,
}: SosCandidateCardProps) {
  const { fullName, profileImageUrl, averageRating, reviewCount, estimatedArrivalMinutes } = candidate;
  const area = candidate.serviceRegion ?? candidate.city;
  const isAccepted = candidate.state === 'ACCEPTED';

  return (
    <Card className={`${styles.card} ${isAccepted ? styles.cardAccepted : styles.cardRequested}`}>
      <div className={styles.top}>
        <SosAvatar
          imageUrl={profileImageUrl}
          fullName={fullName}
          imageClassName={styles.avatar}
          fallbackClassName={styles.avatarFallback}
        />
        <div className={styles.identity}>
          <h3 className={styles.name}>{fullName}</h3>
          {averageRating !== null ? (
            <span className={styles.rating}>
              <Star size={14} className={styles.ratingStar} aria-hidden="true" fill="currentColor" />
              {averageRating.toFixed(1)}
              <span className={styles.reviewCount}>· {formatReviewCount(reviewCount)}</span>
            </span>
          ) : (
            <span className={styles.noRating}>עדיין אין ביקורות</span>
          )}
          {isAccepted ? (
            /* "אישר זמינות", not "אישר" alone. This app reserves the bare word for a professional
               confirming a job they were actually GIVEN (see sosUiState.ts) -- saying it here would
               tell the customer somebody is already on the way. */
            <span className={styles.acceptedBadge}>אישר זמינות ✓</span>
          ) : (
            <span className={styles.waitingLabel}>ממתין לתשובה</span>
          )}
        </div>
        {isAccepted && estimatedArrivalMinutes !== null && (
          <span className={styles.eta}>
            <Clock size={14} aria-hidden="true" />
            כ־{estimatedArrivalMinutes} דק׳
          </span>
        )}
      </div>

      {area && (
        <p className={styles.meta}>
          <MapPin size={15} aria-hidden="true" />
          {area}
          {candidate.distanceKm !== null && ` · ${candidate.distanceKm.toFixed(1)} ק״מ ממך`}
        </p>
      )}

      {/* §13: the surcharge is disclosed, never buried. The total is what the *visit* costs —
          the repair itself is agreed with the professional on site and Pronto takes no part in it. */}
      <div className={styles.priceBreakdown}>
        {candidate.visitFee !== null && (
          <div className={styles.priceRow}>
            <span className={styles.priceLabel}>דמי ביקור</span>
            <span className={styles.priceValue}>{price(candidate.visitFee)}</span>
          </div>
        )}
        <div className={styles.priceRow}>
          <span className={styles.priceLabel}>תוספת קריאה דחופה</span>
          <span className={styles.priceValue}>{price(candidate.sosFee)}</span>
        </div>
        <div className={`${styles.priceRow} ${styles.totalRow}`}>
          <span className={styles.totalLabel}>סה״כ לביקור</span>
          <span className={styles.totalValue}>{price(candidate.totalVisitCost)}</span>
        </div>
      </div>

      <div className={styles.actions}>
        {/* Available whether or not selection is open: the customer can always inspect, they just
            cannot always commit. A details affordance that greyed out with the CTA would remove
            the one thing they *can* usefully do while waiting. */}
        <Button variant="secondary" onClick={() => onOpenDetails(candidate)} fullWidth>
          פרטים נוספים
        </Button>
        {/* No select button at all for a professional who has not answered -- not a disabled one.
            A greyed-out CTA invites the customer to keep trying it; its absence says plainly that
            there is nothing to decide yet. The backend refuses such a selection regardless
            (SOS_CANDIDATE_NOT_AVAILABLE), so this is the honest rendering of a real rule. */}
        {isAccepted && (
          <Button
            onClick={() => onSelect(candidate)}
            disabled={!selectionOpen || isSubmitting}
            loading={isPending}
            fullWidth
          >
            בחר
          </Button>
        )}
      </div>
    </Card>
  );
}
