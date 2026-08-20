import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { ProfessionalCard, type ViewProfileContext } from './ProfessionalCard';
import type { ProfessionalCard as ProfessionalCardData, ProfessionalSort } from '../../shared/api';
import { EmptyState, FilterChipGroup, Skeleton } from '../../shared/components';
import { listStagger, pageTransition } from '../../shared/motion/variants';
import styles from './ProfessionalList.module.css';

/** Beyond this many results, entries render without a per-item stagger wrapper — a
 *  staggered entrance beyond ~8 items reads as slow rather than lively (`variants.ts`'s own
 *  `listStagger` doc comment, design doc §3.A2). */
const STAGGER_CAP = 8;

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
  /** The issue's category, from the same listing response as `professionals` — passed
   *  through to every card for §29's profession line. */
  categoryId?: number;
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
  categoryId,
  isLoading,
  viewProfileContext,
}: ProfessionalListProps) {
  // Same neutralization pattern `IssueSuccessStep.tsx` already uses for `listStagger`/
  // `pageTransition`'s reuse: the `animate` target itself must be overridden to neutralize
  // both the stagger orchestration and each item's own spring under reduced motion.
  const shouldReduceMotion = useReducedMotion();
  const containerAnimate = shouldReduceMotion
    ? { transition: { staggerChildren: 0, delayChildren: 0 } }
    : 'animate';
  const itemAnimate = shouldReduceMotion
    ? { ...(pageTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  if (isLoading) {
    return (
      <div className={styles.wrapper}>
        <div className={styles.list}>
          <Skeleton variant="rect" className={styles.skeleton} />
          <Skeleton variant="rect" className={styles.skeleton} />
          <Skeleton variant="rect" className={styles.skeleton} />
        </div>
      </div>
    );
  }

  return (
    <div className={styles.wrapper}>
      <p className={styles.heading}>מצאנו {professionals.length} בעלי מקצוע מתאימים</p>
      <FilterChipGroup options={sortOptions} value={sort} onChange={onSortChange} aria-label="מיון תוצאות" />
      {professionals.length === 0 ? (
        <EmptyState title="לא נמצאו בעלי מקצוע פנויים" description="אפשר לנסות שוב מאוחר יותר." />
      ) : (
        <motion.div className={styles.list} variants={listStagger} initial="initial" animate={containerAnimate}>
          {professionals.map((professional, index) => {
            const card = (
              <ProfessionalCard
                professional={professional}
                sort={sort}
                categoryId={categoryId}
                // The backend ranked this list; under `RECOMMENDED` its first entry *is* the
                // recommendation (§33). No client-side scoring is invented here, and no badge
                // appears under the other sort modes.
                isTopRecommendation={sort === 'RECOMMENDED' && index === 0}
                onSelect={onSelect}
                viewProfileContext={viewProfileContext}
              />
            );
            return index < STAGGER_CAP ? (
              <motion.div key={professional.professionalId} variants={pageTransition} animate={itemAnimate}>
                {card}
              </motion.div>
            ) : (
              <div key={professional.professionalId}>{card}</div>
            );
          })}
        </motion.div>
      )}
    </div>
  );
}
