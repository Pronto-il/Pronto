import { ProfessionalCard, type ViewProfileContext } from './ProfessionalCard';
import type { ProfessionalCard as ProfessionalCardData, ProfessionalSort } from '../../shared/api';
import styles from './ProfessionalList.module.css';

export interface SortOption {
  value: ProfessionalSort;
  label: string;
}

export interface ProfessionalListProps {
  professionals: ProfessionalCardData[];
  sort: ProfessionalSort;
  sortOptions: SortOption[];
  onSortChange: (sort: ProfessionalSort) => void;
  onSelect: (professional: ProfessionalCardData) => void;
  isLoading?: boolean;
  /** Passed through to every `ProfessionalCard` — see `ProfessionalCardProps` (§2.3). */
  viewProfileContext?: ViewProfileContext;
}

/** Standard-booking flow's sort chips (§34): highest-rated first, or cheapest first. */
export const STANDARD_SORT_OPTIONS: SortOption[] = [
  { value: 'RECOMMENDED', label: 'הכי מומלצים' },
  { value: 'CHEAPEST', label: 'הזולים ביותר' },
];

/** SOS-booking flow's sort chips: identical to Standard's, per §3.2 reconciliation. */
export const SOS_SORT_OPTIONS: SortOption[] = [
  { value: 'RECOMMENDED', label: 'הכי מומלצים' },
  { value: 'CHEAPEST', label: 'הזולים ביותר' },
];

/**
 * Professional-listing results, per DESIGN_SYSTEM.md §42: count heading, sort chips, then
 * cards. Reused by `features/booking`'s listing step — the Standard and SOS flows each pass
 * their own `sortOptions` (§34).
 */
export function ProfessionalList({
  professionals,
  sort,
  sortOptions,
  onSortChange,
  onSelect,
  isLoading,
  viewProfileContext,
}: ProfessionalListProps) {
  if (isLoading) {
    return (
      <div className={styles.wrapper}>
        <div className={styles.list}>
          <div className={styles.skeleton} />
          <div className={styles.skeleton} />
          <div className={styles.skeleton} />
        </div>
      </div>
    );
  }

  return (
    <div className={styles.wrapper}>
      <p className={styles.heading}>מצאנו {professionals.length} בעלי מקצוע מתאימים</p>
      <div className={styles.chips}>
        {sortOptions.map((option) => (
          <button
            key={option.value}
            type="button"
            className={`${styles.chip} ${sort === option.value ? styles.chipActive : ''}`}
            onClick={() => onSortChange(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>
      {professionals.length === 0 ? (
        <div className={styles.empty}>
          <p className={styles.emptyTitle}>לא נמצאו בעלי מקצוע פנויים</p>
          <p>אפשר לנסות שוב מאוחר יותר.</p>
        </div>
      ) : (
        <div className={styles.list}>
          {professionals.map((professional) => (
            <ProfessionalCard
              key={professional.professionalId}
              professional={professional}
              sort={sort}
              onSelect={onSelect}
              viewProfileContext={viewProfileContext}
            />
          ))}
        </div>
      )}
    </div>
  );
}
