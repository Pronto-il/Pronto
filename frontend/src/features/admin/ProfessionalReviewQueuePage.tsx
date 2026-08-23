import { useCallback, useEffect, useState } from 'react';
import { PageHeader, FilterChipGroup, EmptyState, Button, Skeleton } from '../../shared/components';
import type { FilterChipOption } from '../../shared/components';
import {
  listProfessionalsForReview,
  getCategoriesWithSubServices,
  GENERIC_ERROR_MESSAGE,
} from '../../shared/api';
import type {
  ProfessionalApprovalStatus,
  ProfessionalApprovalSummary,
  CategoryWithSubServicesResponse,
} from '../../shared/api';
import { ProfessionalQueueCard } from './ProfessionalQueueCard';
import styles from './ProfessionalReviewQueuePage.module.css';

/**
 * The filters this screen offers. `'ALL'` is its own value for "no filter", sent as an omitted
 * query parameter — the backend has no such status and would reject it.
 *
 * Deliberately **not** every value of `ProfessionalApprovalStatus`: the backend also accepts
 * `DISABLED` as a filter, but nothing in MS1 can produce that state, so offering a chip that can
 * only ever return an empty list would be a UI for a capability that does not exist. MS7 adds the
 * suspend action and the chip together.
 */
type QueueFilter = Exclude<ProfessionalApprovalStatus, 'DISABLED'> | 'ALL';

const FILTER_OPTIONS: FilterChipOption<QueueFilter>[] = [
  { value: 'PENDING', label: 'ממתינים לבדיקה' },
  { value: 'APPROVED', label: 'אושרו' },
  { value: 'REJECTED', label: 'נדחו' },
  { value: 'ALL', label: 'הכול' },
];

const EMPTY_MESSAGE: Record<QueueFilter, { title: string; description: string }> = {
  PENDING: {
    title: 'אין בקשות שממתינות לבדיקה',
    description: 'כל הבקשות שהוגשו עד עכשיו כבר נבדקו.',
  },
  APPROVED: { title: 'אין בעלי מקצוע מאושרים', description: 'עדיין לא אושרה אף בקשה.' },
  REJECTED: { title: 'אין בקשות שנדחו', description: 'עדיין לא נדחתה אף בקשה.' },
  ALL: { title: 'אין בעלי מקצוע במערכת', description: 'עדיין לא נרשם אף בעל מקצוע.' },
};

/**
 * `/admin/professionals` — the operator's review queue (MS1, design
 * `docs/architecture/ms1-professional-verification-design.md` D-F).
 *
 * **This is not an admin console.** It is the one list MS1 needs so the approval lifecycle it
 * introduces has somebody able to drive it: who is waiting, oldest first, and a way into each
 * application. Users, orders, analytics and search belong to MS7 and are deliberately absent.
 *
 * Defaults to the pending filter, because that is the work — the other three exist so an operator
 * can look up a decision they already made. The backend orders by registration date ascending, so
 * whoever has been waiting longest is at the top; that order is not re-sorted here.
 *
 * The category catalog is fetched **best-effort**: it only supplies a category's Hebrew name, and
 * a queue that refuses to render because a secondary lookup failed would be worse than a queue
 * with one line missing. The review screen behaves the same way.
 */
export default function ProfessionalReviewQueuePage() {
  const [filter, setFilter] = useState<QueueFilter>('PENDING');
  const [professionals, setProfessionals] = useState<ProfessionalApprovalSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [catalog, setCatalog] = useState<CategoryWithSubServicesResponse[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    // No retry affordance: a missing category name is a cosmetic gap on this screen.
    getCategoriesWithSubServices()
      .then((result) => {
        if (!cancelled) setCatalog(result);
      })
      .catch(() => {
        if (!cancelled) setCatalog(null);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    setProfessionals(null);
    setError(null);
    listProfessionalsForReview(filter === 'ALL' ? undefined : filter)
      .then((result) => {
        if (!cancelled) setProfessionals(result.professionals);
      })
      .catch(() => {
        if (!cancelled) setError(GENERIC_ERROR_MESSAGE);
      });
    return () => {
      cancelled = true;
    };
  }, [filter, reloadKey]);

  const handleRetry = useCallback(() => setReloadKey((key) => key + 1), []);

  return (
    <div className="page-container">
      <PageHeader
        title="אימות בעלי מקצוע"
        description="בדיקת בקשות הצטרפות של בעלי מקצוע, ואישור או דחייה שלהן."
      />

      <FilterChipGroup
        options={FILTER_OPTIONS}
        value={filter}
        onChange={setFilter}
        aria-label="סינון בקשות לפי מצב"
        className={styles.filters}
      />

      {error && (
        <EmptyState
          tone="error"
          title="לא הצלחנו לטעון את רשימת הבקשות"
          description={error}
          action={
            <Button variant="secondary" onClick={handleRetry}>
              נסו שוב
            </Button>
          }
        />
      )}

      {!error && professionals === null && (
        <div className={styles.list} aria-busy="true">
          <Skeleton radius="var(--radius-lg)" style={{ height: 116 }} />
          <Skeleton radius="var(--radius-lg)" style={{ height: 116 }} />
          <Skeleton radius="var(--radius-lg)" style={{ height: 116 }} />
        </div>
      )}

      {!error && professionals !== null && professionals.length === 0 && (
        <EmptyState title={EMPTY_MESSAGE[filter].title} description={EMPTY_MESSAGE[filter].description} />
      )}

      {!error && professionals !== null && professionals.length > 0 && (
        <>
          <p className={styles.count} role="status">
            {professionals.length === 1 ? 'בקשה אחת' : `${professionals.length} בקשות`}
          </p>
          <div className={styles.list}>
            {professionals.map((professional) => (
              <ProfessionalQueueCard
                key={professional.professionalId}
                professional={professional}
                catalog={catalog}
              />
            ))}
          </div>
        </>
      )}
    </div>
  );
}
