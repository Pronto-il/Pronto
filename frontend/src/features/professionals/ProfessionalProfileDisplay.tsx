import { Star } from 'lucide-react';
import { Card } from '../../shared/components';
import { getCategoryNameHe } from '../../shared/api';
import type { ProfessionalProfileResponse } from '../../shared/api';
import styles from './ProfessionalProfileDisplay.module.css';

export interface ProfessionalProfileDisplayProps {
  professional: Pick<
    ProfessionalProfileResponse,
    | 'fullName'
    | 'categoryId'
    | 'serviceArea'
    | 'city'
    | 'bio'
    | 'basePrice'
    | 'profileImageUrl'
    | 'averageRating'
    | 'reviewCount'
  >;
}

/**
 * Shared presentational block (MS6 Professional Command Center design doc §7.1): photo, name,
 * category, rating row, service-area/city/price rows, bio — extracted from
 * `ProfessionalProfilePage.tsx`'s previously-inline identity/info/bio JSX, the part that's
 * genuinely duplicative between "the real public page" and "a live preview of unsaved edits"
 * (`ProfileEditorPage.tsx`). Co-located next to `ProfessionalProfilePage.tsx`, mirroring
 * `ReviewList.tsx`'s own "co-locate until there's a second consumer" precedent already
 * established in this module (`frontend-ms8-design.md` §5).
 *
 * **Not** included here: the favorite button, the reviews section, or the "select
 * professional" CTA — those are live-page-only concerns (an unsaved draft has no favorite
 * state, no review history of its own, and nothing to "select") and stay inline in
 * `ProfessionalProfilePage.tsx`, composed around this component.
 */
export function ProfessionalProfileDisplay({ professional }: ProfessionalProfileDisplayProps) {
  return (
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
    </>
  );
}
