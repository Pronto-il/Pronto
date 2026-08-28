import { Link } from 'react-router-dom';
import { Heart, MapPin, Sparkles, Star, Zap } from 'lucide-react';
import { Badge, Card, Button } from '../../shared/components';
import { getCategoryNameHe } from '../../shared/api';
import type { ProfessionalCard as ProfessionalCardData, ProfessionalSort } from '../../shared/api';
import { formatExtraCategoryCount, formatReviewCount } from '../../shared/utils/hebrewText';
import styles from './ProfessionalCard.module.css';

/** Carried via router `state` (not a query param, see `frontend-ms8-design.md` §2.3) so the
 *  professional-detail page can offer a "select professional" CTA that resumes the exact
 *  flow this card was rendered from. Deliberately transient — lost on refresh/direct visit,
 *  an accepted degradation to a view-only page. */
export interface ViewProfileContext {
  /** Optional as of deferred authentication: a guest browsing the listing has no issue yet, and
   *  the profile screen only uses this to offer a "back to your results" affordance. */
  issueId?: number;
  urgencyType: 'STANDARD' | 'SOS';
}

/** The actual `location.state` shape landed on by `/professionals/:id` — `fromIssueId`
 *  (not `issueId`) to read unambiguously on a page that has no "current issue" of its own. */
export interface ProfessionalDetailLocationState {
  fromIssueId?: number;
  urgencyType: 'STANDARD' | 'SOS';
}

export interface ProfessionalCardProps {
  professional: ProfessionalCardData;
  /** Which sort mode is active — only changes visual emphasis, never the card structure (DESIGN_SYSTEM.md §32/FRONTEND_AGENT.md §12). */
  sort?: ProfessionalSort;
  /**
   * The issue's service category, from the listing response (`ProfessionalListingResponse.
   * categoryId`). Renders the profession line DESIGN_SYSTEM.md §29 places directly under the
   * name. Optional: a card rendered without listing context (none today) simply omits the line
   * rather than guessing.
   *
   * **MS4:** this is the category the customer searched for, and it stays the headline —
   * every professional in the listing serves it, and it is the one they were found for. What
   * changed is that a professional may serve others too, so the card additionally shows a
   * "+N תחומים נוספים" note built from the card's own `categoryIds`; the full list lives on the
   * profile.
   */
  categoryId?: number;
  /**
   * Marks this card as the listing's top recommendation — the first result while the
   * `RECOMMENDED` sort is active, i.e. the professional the backend's own ranking put first.
   * Renders §33's "מומלץ עבורך" badge. Never set from a client-side heuristic, and never set
   * at all under the other sort modes (§32: sorting changes emphasis, not structure).
   */
  isTopRecommendation?: boolean;
  onSelect: (professional: ProfessionalCardData) => void;
  /** When provided, the identity block (photo + name) becomes a secondary link to
   *  `/professionals/:id` carrying this as router `state` — the primary `onSelect` button is
   *  unchanged either way (`frontend-ms8-design.md` §2.3). */
  viewProfileContext?: ViewProfileContext;
}

function initials(fullName: string): string {
  const parts = fullName.trim().split(/\s+/);
  const first = parts[0]?.[0] ?? '';
  const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (first + last).toUpperCase();
}

/**
 * Professional profile-summary card, per DESIGN_SYSTEM.md §29-33: photo, name, profession,
 * rating + review count (an honest "עדיין אין ביקורות" when `averageRating` is null — never
 * a fabricated "0.0" or "0 reviews"), service area + distance + ETA, price, single primary
 * CTA.
 *
 * **MS4 final corrections (2026-08-20)**: the profession line (§29's hierarchy) and §33's
 * "מומלץ עבורך" badge on the top `RECOMMENDED` result were both missing — the badge is the
 * one element §32 names as the recommended mode's primary comparison signal, and the shared
 * `Badge` component's `tone="primary"` had been built for it in MS1 without ever being used.
 * No verification checkmark is rendered here despite §29's example: the listing DTO carries
 * no approval/verification field and the listing endpoint filters only on "not deleted"
 * (`BookingsService.isProfessionalActive`), so claiming verification on this surface would
 * violate §44. The profile page, which does receive `approvalStatus`, shows it instead.
 *
 * Reused by both the Standard and SOS listings (`features/booking`'s `BookingFlowPage`/`SosBookingFlowPage`, both via
 * `ProfessionalList`) — identical card structure for both flows. `favorited` is rendered
 * read-only this pass (no toggle interaction — that needs `POST`/`DELETE /api/favorites`,
 * out of scope).
 */
export function ProfessionalCard({
  professional,
  sort,
  categoryId,
  isTopRecommendation,
  onSelect,
  viewProfileContext,
}: ProfessionalCardProps) {
  const {
    professionalId,
    fullName,
    serviceRegion,
    basePrice,
    profileImageUrl,
    averageRating,
    reviewCount,
    distanceKm,
    etaMinutes,
    favorited,
    categoryIds,
  } = professional;

  /** How many *other* trades this professional serves beyond the one being searched for. */
  const extraCategoryLabel = formatExtraCategoryCount(
    categoryIds.filter((id) => id !== categoryId).length,
  );

  const identityContent = (
    <>
      {profileImageUrl ? (
        <img src={profileImageUrl} alt="" className={styles.avatar} />
      ) : (
        <span className={styles.avatarFallback} aria-hidden="true">
          {initials(fullName)}
        </span>
      )}
      <div className={styles.identity}>
        <h3 className={styles.name}>
          {fullName}
          {favorited && (
            <Heart size={15} className={styles.favoriteMark} aria-label="שמור במועדפים" fill="currentColor" />
          )}
        </h3>
        {categoryId !== undefined && (
          <p className={styles.profession}>
            {getCategoryNameHe(categoryId)}
            {extraCategoryLabel && <span className={styles.professionExtra}> {extraCategoryLabel}</span>}
          </p>
        )}
        {averageRating !== null ? (
          <span className={`${styles.rating} ${sort === 'RECOMMENDED' ? styles.ratingEmphasis : ''}`}>
            <Star size={14} className={styles.ratingStar} aria-hidden="true" fill="currentColor" />
            {averageRating.toFixed(1)}
            <span className={styles.reviewCount}>· {formatReviewCount(reviewCount)}</span>
          </span>
        ) : (
          // Stated plainly rather than left blank — an absent rating row reads as a missing
          // element, while "no reviews yet" is honest and is not a trust claim (§44).
          <span className={styles.noRating}>עדיין אין ביקורות</span>
        )}
      </div>
    </>
  );

  return (
    <Card className={`${styles.card} ${isTopRecommendation ? styles.cardRecommended : ''}`}>
      {isTopRecommendation && (
        <Badge tone="primary" size="sm" icon={<Sparkles size={14} />} className={styles.recommendedBadge}>
          מומלץ עבורך
        </Badge>
      )}

      {viewProfileContext ? (
        <Link
          to={`/professionals/${professionalId}`}
          state={{ fromIssueId: viewProfileContext.issueId, urgencyType: viewProfileContext.urgencyType }}
          className={`${styles.top} ${styles.topLink}`}
        >
          {identityContent}
        </Link>
      ) : (
        <div className={styles.top}>{identityContent}</div>
      )}

      <div className={styles.meta}>
        {/*
          Production MS2. `etaMinutes`/`distanceKm` are nullable now, and the null case is a
          real, ordinary outcome rather than an edge: the professional's device position may be
          missing, stale or too coarse to route from, or the maps provider may be unreachable.

          What is NOT done here is the tempting thing -- rendering `0 דקות` / `0.0 ק״מ`, or
          silently hiding the professional. The first is a lie the customer would act on; the
          second removes somebody perfectly bookable for next Tuesday because their phone is in
          a basement right now. So the card stays, and the travel line says plainly that the
          figure is unavailable.
        */}
        {etaMinutes !== null ? (
          <span className={`${styles.metaItem} ${styles.eta} ${sort === 'FASTEST' ? styles.etaEmphasis : ''}`}>
            <Zap size={16} aria-hidden="true" />
            יכול להגיע תוך כ־{etaMinutes} דקות
          </span>
        ) : (
          <span className={`${styles.metaItem} ${styles.etaUnavailable}`}>
            <Zap size={16} aria-hidden="true" />
            זמן הגעה לא זמין כרגע
          </span>
        )}
        <span className={styles.metaItem}>
          <MapPin size={15} aria-hidden="true" />
          {serviceRegion ?? 'אזור לא הוגדר'}
          {/* The distance clause is dropped entirely rather than replaced with a placeholder --
              the region is genuine information and reads perfectly well on its own. */}
          {distanceKm !== null && ` · ${distanceKm.toFixed(1)} ק״מ ממך`}
        </span>
      </div>

      <div className={styles.bottom}>
        <div className={styles.priceBlock}>
          <span className={styles.priceLabel}>מחיר ביקור</span>
          <span className={`${styles.price} ${sort === 'CHEAPEST' ? styles.priceEmphasis : ''}`}>
            ₪{basePrice}
          </span>
        </div>
        <Button onClick={() => onSelect(professional)}>בחירת בעל מקצוע</Button>
      </div>
    </Card>
  );
}
