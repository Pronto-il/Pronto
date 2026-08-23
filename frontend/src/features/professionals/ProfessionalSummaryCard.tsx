import { ShieldCheck, Star } from 'lucide-react';
import { Badge, Card } from '../../shared/components';
import { getCategoryNameHe } from '../../shared/api';
import type { ProfessionalProfileResponse } from '../../shared/api';
import { formatMonthYearLabel } from '../../shared/utils/formatDateTime';
import { formatReviewCount } from '../../shared/utils/hebrewText';
import styles from './ProfessionalSummaryCard.module.css';

export interface ProfessionalSummaryCardProps {
  professional: Pick<
    ProfessionalProfileResponse,
    'fullName' | 'categoryId' | 'profileImageUrl' | 'averageRating' | 'reviewCount'
  > &
    Partial<Pick<ProfessionalProfileResponse, 'approvalStatus' | 'createdAt' | 'city' | 'serviceArea'>>;
}

function initials(fullName: string): string {
  const parts = fullName.trim().split(/\s+/);
  const first = parts[0]?.[0] ?? '';
  const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (first + last).toUpperCase();
}

/**
 * The compact form of `ProfessionalProfileDisplay` — the same identity signals (photo, name,
 * profession, verification, rating + review count, "on Pronto since"), laid out as one horizontal
 * card instead of a full page block, for surfaces whose subject is *an order* rather than the
 * professional themselves.
 *
 * Every field is read straight off the real profile DTO, exactly as the full display does —
 * nothing here is derived, defaulted or invented. An unrated professional says so ("עדיין אין
 * ביקורות"), matching `ProfessionalCard`'s wording rather than showing a fabricated 0.0, and the
 * verification badge renders only for a genuinely `APPROVED` profile (§44).
 *
 * Purely presentational: it fetches nothing and owns no state, so a caller that already has a
 * profile in hand can render it directly.
 */
export function ProfessionalSummaryCard({ professional }: ProfessionalSummaryCardProps) {
  return (
    <Card className={styles.card}>
      {professional.profileImageUrl ? (
        <img src={professional.profileImageUrl} alt="" className={styles.photo} />
      ) : (
        <span className={styles.photoFallback} aria-hidden="true">
          {initials(professional.fullName)}
        </span>
      )}

      <div className={styles.body}>
        <p className={styles.name}>{professional.fullName}</p>
        <p className={styles.category}>{getCategoryNameHe(professional.categoryId)}</p>

        <div className={styles.signals}>
          {professional.averageRating !== null ? (
            <span className={styles.rating}>
              <Star size={14} className={styles.ratingStar} aria-hidden="true" fill="currentColor" />
              {professional.averageRating.toFixed(1)}
              <span className={styles.reviewCount}>· {formatReviewCount(professional.reviewCount)}</span>
            </span>
          ) : (
            <span className={styles.noRating}>עדיין אין ביקורות</span>
          )}
          {professional.createdAt && (
            <span className={styles.since}>בפרונטו מאז {formatMonthYearLabel(professional.createdAt)}</span>
          )}
        </div>

        {professional.approvalStatus === 'APPROVED' && (
          <Badge tone="success" size="sm" icon={<ShieldCheck size={14} />}>
            בעל מקצוע מאומת
          </Badge>
        )}
      </div>
    </Card>
  );
}
