import { Link } from 'react-router-dom';
import { ChevronLeft } from 'lucide-react';
import { Card, Badge } from '../../shared/components';
import type { ProfessionalApprovalSummary, CategoryWithSubServicesResponse } from '../../shared/api';
import { formatDateTimeLabel } from '../../shared/utils/formatDateTime';
import { describeDecision, describeOnboarding } from './approvalPresentation';
import { findCategoryNameHe } from './serviceCatalog';
import styles from './ProfessionalQueueCard.module.css';

export interface ProfessionalQueueCardProps {
  professional: ProfessionalApprovalSummary;
  /** `null` while loading or after a failed catalog fetch — the card then omits the category
   *  name rather than showing an id. */
  catalog: CategoryWithSubServicesResponse[] | null;
}

/**
 * One row of the review queue: who applied, in which field, when, what was decided, and whether
 * they finished their own registration. Enough to choose which application to open next, and
 * deliberately no more — the professional's details, their documents and the decision buttons all
 * live one click away, on the review screen, one professional at a time.
 *
 * The second badge is the D4 signal in list form: an application can read "אושר" and still be
 * missing the material that would make the professional reachable by customers. Both badges come
 * straight from the backend's own fields.
 */
export function ProfessionalQueueCard({ professional, catalog }: ProfessionalQueueCardProps) {
  const decision = describeDecision(professional.approvalStatus);
  const onboarding = describeOnboarding(professional.onboardingComplete);
  const categoryNameHe = findCategoryNameHe(catalog, professional.categoryId);
  const area = [professional.city, professional.serviceArea].filter(Boolean).join(' · ');

  return (
    <Card interactive className={styles.card}>
      <Link
        to={`/admin/professionals/${professional.professionalId}`}
        className={styles.link}
        aria-label={`פתיחת הבקשה של ${professional.fullName ?? 'בעל מקצוע'}`}
      >
        <div className={styles.main}>
          <div className={styles.identity}>
            <p className={styles.name}>{professional.fullName ?? 'שם לא זמין'}</p>
            {professional.email && <p className={styles.email}>{professional.email}</p>}
          </div>

          <dl className={styles.facts}>
            {categoryNameHe && (
              <div className={styles.fact}>
                <dt>תחום</dt>
                <dd>{categoryNameHe}</dd>
              </div>
            )}
            {area && (
              <div className={styles.fact}>
                <dt>אזור שירות</dt>
                <dd>{area}</dd>
              </div>
            )}
            <div className={styles.fact}>
              <dt>נרשם</dt>
              <dd>{formatDateTimeLabel(professional.registeredAt)}</dd>
            </div>
          </dl>

          <div className={styles.badges}>
            <Badge tone={decision.tone} size="sm">
              {decision.label}
            </Badge>
            <Badge tone={onboarding.tone} size="sm">
              {onboarding.label}
            </Badge>
          </div>
        </div>

        <span className={styles.chevron} aria-hidden="true">
          <ChevronLeft size={20} />
        </span>
      </Link>
    </Card>
  );
}
