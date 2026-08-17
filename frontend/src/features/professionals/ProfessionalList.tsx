import { ProfessionalCard } from './ProfessionalCard';
import type { ProfessionalCard as ProfessionalCardData, ProfessionalSort } from '../../shared/api';
import styles from './ProfessionalList.module.css';

export interface ProfessionalListProps {
  professionals: ProfessionalCardData[];
  sort: ProfessionalSort;
  onSortChange: (sort: ProfessionalSort) => void;
  onSelect: (professional: ProfessionalCardData) => void;
  isLoading?: boolean;
}

const SORT_OPTIONS: { value: ProfessionalSort; label: string }[] = [
  { value: 'CHEAPEST', label: 'הזולים ביותר' },
  { value: 'FASTEST', label: 'הכי מהירים' },
];

/**
 * Professional-listing results, per DESIGN_SYSTEM.md §42: count heading, sort chips
 * (§34 — there is no server-side "Recommended" mode, only `CHEAPEST`/`FASTEST`, so only
 * those two chips are offered), then cards. Reused by `features/booking`'s listing step.
 */
export function ProfessionalList({ professionals, sort, onSortChange, onSelect, isLoading }: ProfessionalListProps) {
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
        {SORT_OPTIONS.map((option) => (
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
            />
          ))}
        </div>
      )}
    </div>
  );
}
