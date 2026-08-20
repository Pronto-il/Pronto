import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { PageHeader, StatusBadge, Button, EmptyState, Skeleton } from '../../shared/components';
import { getMyOrders, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { OrderSummary, OrderStatus } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './MyOrdersPage.module.css';

/**
 * Terminal-for-sectioning statuses (mirrors `features/dashboard/MyJobsPage.tsx`'s
 * `HISTORY_STATUSES`, MS4 design doc §4 Q1's resolved decision): a literal reading of
 * "History" would only include `COMPLETED`, but `CANCELLED`/`REJECTED`/`EXPIRED` orders were
 * already visible on this page before this milestone (no status filter) — dropping them from
 * a sectioned page would silently remove functionality that exists today. All four fold into
 * History instead, each still carrying its own accurate `StatusBadge`.
 */
const HISTORY_STATUSES: readonly OrderStatus[] = ['COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED'];

interface OrderSections {
  active: OrderSummary[];
  history: OrderSummary[];
}

/**
 * Pure client-side bucketing over the page's already-fetched, unfiltered `OrderSummary[]` — no
 * new endpoint, no change to `getMyOrders()` (MS4 design doc §4 Q1). Unlike the professional
 * side's `MyJobsPage.tsx` (three sections: today/upcoming/history), this page uses exactly two
 * sections per this milestone's decision — everything not in `HISTORY_STATUSES` is a single
 * combined "Active/Upcoming" bucket, sorted soonest-first.
 */
function bucketOrders(orders: OrderSummary[]): OrderSections {
  const active: OrderSummary[] = [];
  const history: OrderSummary[] = [];

  for (const order of orders) {
    if (HISTORY_STATUSES.includes(order.orderStatus)) {
      history.push(order);
    } else {
      active.push(order);
    }
  }

  active.sort((a, b) => a.bookedStart.localeCompare(b.bookedStart));
  history.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));

  return { active, history };
}

function OrderRow({ order }: { order: OrderSummary }) {
  return (
    <Link to={`/orders/${order.id}`} className={styles.row}>
      <div className={styles.rowMain}>
        <span className={styles.rowDate}>
          {formatDateLabel(order.bookedStart)}, {formatTimeLabel(order.bookedStart)}
        </span>
        <span className={styles.rowPrice}>₪{order.finalPrice}</span>
      </div>
      <StatusBadge status={order.orderStatus} />
    </Link>
  );
}

/**
 * `/orders` — the caller's own orders, `CUSTOMER`-only per this milestone's route map (the
 * professional side has no equivalent screen yet — its own request/job history is out of
 * `features/dashboard`'s scope this pass). Each row links to `/orders/:id` for the full
 * tracking view. Empty state per DESIGN_SYSTEM.md §60/FRONTEND_AGENT.md §25.
 *
 * MS4 design doc §4 Q1: the previously-flat list is now split into Active/Upcoming and
 * History sections, purely client-side (`bucketOrders`, no new endpoint), mirroring
 * `features/dashboard/MyJobsPage.tsx`'s analogous professional-side pattern. This is strictly
 * an IA change — row markup, the fetch, and error handling are otherwise unchanged.
 */
export default function MyOrdersPage() {
  const navigate = useNavigate();
  const [orders, setOrders] = useState<OrderSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getMyOrders()
      .then((result) => {
        if (!cancelled) {
          setOrders(result.orders);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setError(GENERIC_ERROR_MESSAGE);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const sections = useMemo(() => bucketOrders(orders ?? []), [orders]);

  return (
    <div className="focused-page">
      <PageHeader title="ההזמנות שלי" />

      {error && (
        <div className={styles.banner} role="alert">
          <p>{error}</p>
        </div>
      )}

      {!error && orders === null && (
        <div className={styles.list}>
          <Skeleton variant="rect" className={styles.skeletonRow} />
          <Skeleton variant="rect" className={styles.skeletonRow} />
          <Skeleton variant="rect" className={styles.skeletonRow} />
        </div>
      )}

      {orders !== null && orders.length === 0 && (
        <EmptyState
          title="אין עדיין הזמנות"
          description="כשאתם מזמינים בעל מקצוע, ההזמנות שלכם יופיעו כאן."
          action={<Button onClick={() => navigate('/issues/new')}>יש לי תקלה</Button>}
        />
      )}

      {orders !== null && orders.length > 0 && (
        <div className={styles.sections}>
          <section>
            <p className={styles.sectionTitle}>פעילות וקרובות</p>
            {sections.active.length === 0 ? (
              <EmptyState
                title="אין הזמנות פעילות כרגע"
                description="הזמנות פעילות ועתידיות יופיעו כאן."
              />
            ) : (
              <div className={styles.list}>
                {sections.active.map((order) => (
                  <OrderRow key={order.id} order={order} />
                ))}
              </div>
            )}
          </section>

          <section>
            <p className={styles.sectionTitle}>היסטוריה</p>
            {sections.history.length === 0 ? (
              <EmptyState
                title="אין עדיין היסטוריית הזמנות"
                description="הזמנות שהושלמו, בוטלו או פג תוקפן יופיעו כאן."
              />
            ) : (
              <div className={styles.list}>
                {sections.history.map((order) => (
                  <OrderRow key={order.id} order={order} />
                ))}
              </div>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
