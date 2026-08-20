import { Link } from 'react-router-dom';
import { Star, Heart } from 'lucide-react';
import { Card } from '../../shared/components';
import type { FavoriteProfessionalSummary } from '../../shared/api';
import { formatReviewCount } from '../../shared/utils/hebrewText';
import styles from './FavoriteProfessionalCard.module.css';

export interface FavoriteProfessionalCardProps {
  favorite: FavoriteProfessionalSummary;
  onRemove: (professionalId: number) => void;
  isRemoving?: boolean;
}

function initials(fullName: string): string {
  const parts = fullName.trim().split(/\s+/);
  const first = parts[0]?.[0] ?? '';
  const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (first + last).toUpperCase();
}

/**
 * Lean favorites-list card — deliberately not a reuse of `features/professionals`'s
 * `ProfessionalCard`: `FavoriteProfessionalSummary` has no `distanceKm`/`etaMinutes`/
 * `sameCity` fields at all, which `ProfessionalCard`'s prop type requires as non-nullable
 * (`frontend-ms8-design.md` §4.2). Click-through to `/professionals/:id` with no router
 * `state` — a favorites-list visit has no issue/flow context, correctly producing a
 * view-only detail page (§2.3).
 */
export function FavoriteProfessionalCard({ favorite, onRemove, isRemoving }: FavoriteProfessionalCardProps) {
  const { professionalId, fullName, serviceArea, city, basePrice, profileImageUrl, averageRating, reviewCount } =
    favorite;

  return (
    <Card className={styles.card}>
      <Link to={`/professionals/${professionalId}`} className={styles.identityLink}>
        {profileImageUrl ? (
          <img src={profileImageUrl} alt="" className={styles.avatar} />
        ) : (
          <span className={styles.avatarFallback} aria-hidden="true">
            {initials(fullName)}
          </span>
        )}
        <div className={styles.identity}>
          <h3 className={styles.name}>{fullName}</h3>
          <p className={styles.serviceArea}>
            {serviceArea}
            {city ? ` · ${city}` : ''}
          </p>
          {averageRating !== null && (
            <span className={styles.rating}>
              <Star size={14} className={styles.ratingStar} aria-hidden="true" fill="currentColor" />
              {averageRating.toFixed(1)}
              <span className={styles.reviewCount}>· {formatReviewCount(reviewCount)}</span>
            </span>
          )}
        </div>
      </Link>

      <div className={styles.bottom}>
        <span className={styles.price}>₪{basePrice}</span>
        <button
          type="button"
          className={styles.removeButton}
          onClick={() => onRemove(professionalId)}
          disabled={isRemoving}
        >
          <Heart size={16} aria-hidden="true" fill="currentColor" />
          <span>הסרה ממועדפים</span>
        </button>
      </div>
    </Card>
  );
}
