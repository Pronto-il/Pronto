import { ShieldCheck, Star } from 'lucide-react';
import { Badge, Card } from '../../shared/components';
import { getCategoryNameHe } from '../../shared/api';
import type { ProfessionalProfileResponse } from '../../shared/api';
import { formatMonthYearLabel } from '../../shared/utils/formatDateTime';
import { formatReviewCount } from '../../shared/utils/hebrewText';
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
  > &
    /** Trust signals (§44) — optional so a caller that has no real value for them (rather than
     *  a false one) simply omits the corresponding element. */
    Partial<Pick<ProfessionalProfileResponse, 'approvalStatus' | 'createdAt'>>;
}

/**
 * Shared presentational block (MS6 Professional Command Center design doc §7.1): photo, name,
 * category, verification badge, rating row, stats strip, service-area/city/price rows, bio —
 * extracted from
 * `ProfessionalProfilePage.tsx`'s previously-inline identity/info/bio JSX, the part that's
 * genuinely duplicative between "the real public page" and "a live preview of unsaved edits"
 * (`ProfileEditorPage.tsx`). Co-located next to `ProfessionalProfilePage.tsx`, mirroring
 * `ReviewList.tsx`'s own "co-locate until there's a second consumer" precedent already
 * established in this module (`frontend-ms8-design.md` §5).
 *
 * **MS4 final corrections (2026-08-20)**: the page carried no trust signals at all beyond an
 * optional rating — §44 asks for verification near the identity, and the profile DTO does
 * carry `approvalStatus`/`createdAt`. Both are rendered here strictly from that real data
 * (the badge only for `APPROVED`), which is why the listing card, whose DTO has neither
 * field, still shows no verification mark.
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
        {professional.approvalStatus === 'APPROVED' && (
          <Badge tone="success" size="sm" icon={<ShieldCheck size={14} />}>
            בעל מקצוע מאומת
          </Badge>
        )}
        {professional.averageRating !== null && (
          <span className={styles.rating}>
            <Star size={16} className={styles.ratingStar} aria-hidden="true" fill="currentColor" />
            {professional.averageRating.toFixed(1)}
            <span className={styles.reviewCount}>· {formatReviewCount(professional.reviewCount)}</span>
          </span>
        )}
      </div>

      {/* §43's stats strip, built only from signals the backend actually returns — no job
          count (not exposed by any endpoint) and no ETA (needs a customer address this page
          has no access to). An unrated professional shows "—", never a fabricated 0.0. */}
      <div className={styles.stats}>
        <div className={styles.stat}>
          <span className={styles.statValue}>
            {professional.averageRating !== null ? professional.averageRating.toFixed(1) : '—'}
          </span>
          <span className={styles.statLabel}>דירוג ממוצע</span>
        </div>
        <div className={styles.stat}>
          <span className={styles.statValue}>{professional.reviewCount}</span>
          <span className={styles.statLabel}>ביקורות</span>
        </div>
        {professional.createdAt && (
          <div className={styles.stat}>
            <span className={styles.statValue}>{formatMonthYearLabel(professional.createdAt)}</span>
            <span className={styles.statLabel}>בפרונטו מאז</span>
          </div>
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
