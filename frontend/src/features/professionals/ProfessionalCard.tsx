import { Star, Zap } from 'lucide-react';
import { Card, Button } from '../../shared/components';
import type { ProfessionalCard as ProfessionalCardData, ProfessionalSort } from '../../shared/api';
import styles from './ProfessionalCard.module.css';

export interface ProfessionalCardProps {
  professional: ProfessionalCardData;
  /** Which sort mode is active — only changes visual emphasis, never the card structure (DESIGN_SYSTEM.md §32/FRONTEND_AGENT.md §12). */
  sort?: ProfessionalSort;
  onSelect: (professional: ProfessionalCardData) => void;
}

function initials(fullName: string): string {
  const parts = fullName.trim().split(/\s+/);
  const first = parts[0]?.[0] ?? '';
  const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (first + last).toUpperCase();
}

/**
 * Professional profile-summary card, per DESIGN_SYSTEM.md §29-33: photo, name, rating +
 * review count (omitted entirely when `averageRating` is null — never shown as "0
 * reviews"), distance + ETA, price, single primary CTA. Reused by both the Standard and
 * SOS listings (`features/booking`'s `BookingFlowPage`/`SosBookingFlowPage`, both via
 * `ProfessionalList`) — identical card structure for both flows. `favorited` is rendered
 * read-only this pass (no toggle interaction — that needs `POST`/`DELETE /api/favorites`,
 * out of scope).
 */
export function ProfessionalCard({ professional, sort, onSelect }: ProfessionalCardProps) {
  const {
    fullName,
    serviceArea,
    basePrice,
    profileImageUrl,
    averageRating,
    reviewCount,
    distanceKm,
    etaMinutes,
    favorited,
  } = professional;

  return (
    <Card className={styles.card}>
      <div className={styles.top}>
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
            {favorited && <span aria-hidden="true"> ♥</span>}
          </h3>
          <p className={styles.serviceArea}>{serviceArea}</p>
          {averageRating !== null && (
            <span className={`${styles.rating} ${sort === 'RECOMMENDED' ? styles.ratingEmphasis : ''}`}>
              <Star size={14} className={styles.ratingStar} aria-hidden="true" fill="currentColor" />
              {averageRating.toFixed(1)}
              <span className={styles.reviewCount}>· {reviewCount} ביקורות</span>
            </span>
          )}
        </div>
      </div>

      <div className={styles.meta}>
        <span className={`${styles.eta} ${sort === 'FASTEST' ? styles.etaEmphasis : ''}`}>
          <Zap size={16} aria-hidden="true" />
          יכול להגיע תוך כ־{etaMinutes} דקות
        </span>
        <span className={styles.distance}>{distanceKm.toFixed(1)} ק״מ ממך</span>
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
