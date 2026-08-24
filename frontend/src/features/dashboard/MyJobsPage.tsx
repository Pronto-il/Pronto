import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { StatusBadge, EmptyState } from '../../shared/components';
import { usePolling } from '../../shared/hooks';
import { getMyOrders, GENERIC_ERROR_MESSAGE, MY_ORDERS_KEY } from '../../shared/api';
import type { MyOrdersResponse, OrderSummary, OrderStatus } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './MyJobsPage.module.css';

/**
 * Terminal-for-sectioning statuses (MS6 design doc §5.2's resolved decision): a literal
 * reading of "Completed" would only include `COMPLETED`, but `CANCELLED`/`REJECTED`/`EXPIRED`
 * orders were already visible on this page before this milestone (no status filter) —
 * dropping them from the sectioned page would silently remove functionality that exists
 * today (`FRONTEND_AGENT.md` §52). All four fold into the third section instead, each still
 * carrying its own accurate `StatusBadge` so nothing is mislabeled as "completed" when it was
 * actually cancelled/rejected/expired.
 */
const HISTORY_STATUSES: readonly OrderStatus[] = ['COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED'];

/** Today: an `ON_THE_WAY`/`CONFIRMED` order sorts before a same-day `PENDING` one (design doc
 *  §5.2's "light status-hierarchy emphasis") — reuses `StatusBadge`'s own status vocabulary,
 *  no new colors/statuses invented. */
const TODAY_STATUS_RANK: Partial<Record<OrderStatus, number>> = {
  ON_THE_WAY: 0,
  CONFIRMED: 0,
  PENDING: 1,
};

function startOfDay(date: Date): number {
  const d = new Date(date);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

interface JobSections {
  today: OrderSummary[];
  upcoming: OrderSummary[];
  history: OrderSummary[];
}

/**
 * Pure client-side bucketing over the page's already-fetched, unfiltered `OrderSummary[]` — no
 * new endpoint (design doc §5.2). `today`/`upcoming` are date-bucketed off `bookedStart` in the
 * browser's local timezone, matching every other date-bucketing already in this app (see
 * `WeeklyCalendarGrid.tsx`'s own documented timezone-assumption precedent). A past-dated order
 * that never reached a terminal status (a rare edge case — e.g. an overdue `PENDING`) falls
 * into `history` too, since it no longer belongs in "today" or "upcoming" — every order lands
 * in exactly one section.
 */
function bucketOrders(orders: OrderSummary[]): JobSections {
  const todayStart = startOfDay(new Date());
  const today: OrderSummary[] = [];
  const upcoming: OrderSummary[] = [];
  const history: OrderSummary[] = [];

  for (const order of orders) {
    if (HISTORY_STATUSES.includes(order.orderStatus)) {
      history.push(order);
      continue;
    }
    const bookedDayStart = startOfDay(new Date(order.bookedStart));
    if (bookedDayStart === todayStart) {
      today.push(order);
    } else if (bookedDayStart > todayStart) {
      upcoming.push(order);
    } else {
      history.push(order);
    }
  }

  today.sort((a, b) => {
    const rankDiff = (TODAY_STATUS_RANK[a.orderStatus] ?? 1) - (TODAY_STATUS_RANK[b.orderStatus] ?? 1);
    return rankDiff !== 0 ? rankDiff : a.bookedStart.localeCompare(b.bookedStart);
  });
  upcoming.sort((a, b) => a.bookedStart.localeCompare(b.bookedStart));
  history.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));

  return { today, upcoming, history };
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
 * "העבודות שלי" tab (`/pro/jobs`) — the professional's only way, besides typing an order
 * URL directly, to see a job again after it leaves the "בקשות חדשות" pending feed
 * (`IncomingRequestsPage`, `GET .../me?status=PENDING`). Calls `getMyOrders()` with no
 * status filter, mirroring `features/booking/MyOrdersPage.tsx`'s analogous customer-side
 * pattern (list everything the caller is party to, no client-side filtering) rather than
 * excluding `PENDING` — the pending feed is the action screen, this is the read-only
 * reference list, and duplication between the two is harmless.
 *
 * Read-only by design: this list only links into `/orders/{id}` for detail/status and any
 * available actions — on-the-way/complete actions now exist (Frontend MS6) but live on
 * `OrderTrackingPage`, not here. This list itself stays link-only, matching the customer-side
 * `MyOrdersPage.tsx` pattern.
 *
 * **MS6 Professional Command Center (design doc §5)**: the flat list is now sectioned into
 * Today/Upcoming/היסטוריה, purely client-side (`bucketOrders`, no new endpoint) — the fetch,
 * link-only rows, and no-inline-actions behavior are all otherwise unchanged.
 */
export default function MyJobsPage() {
  // One-shot read of the shared `GET /api/bookings/orders/me` entry: this is a reference list,
  // not a live board, so it has no cadence of its own. Keying it means tabbing away to another
  // `/pro/*` screen and back re-renders from what is already held — but only while that is still
  // recent, so flipping tabs after accepting a job does not show the list from before it.
  const { data, error: loadError } = usePolling<MyOrdersResponse>(() => getMyOrders(), {
    key: MY_ORDERS_KEY,
    enabled: false,
    fetchOnMountWhenDisabled: true,
    maxStaleOnMountMs: 15_000,
  });
  const orders = data?.orders ?? null;
  const error = loadError ? GENERIC_ERROR_MESSAGE : null;

  const sections = useMemo(() => bucketOrders(orders ?? []), [orders]);

  return (
    <div>
      {error && (
        <div className={styles.banner} role="alert">
          <p>{error}</p>
        </div>
      )}

      {!error && orders === null && <p>טוען…</p>}

      {orders !== null && orders.length === 0 && (
        <div className={styles.empty}>
          <p className={styles.emptyTitle}>אין עדיין עבודות</p>
          <p>עבודות שאישרתם או שכבר טופלו יופיעו כאן.</p>
        </div>
      )}

      {orders !== null && orders.length > 0 && (
        <div className={styles.sections}>
          <section>
            <p className={styles.sectionTitle}>היום</p>
            {sections.today.length === 0 ? (
              <EmptyState title="אין עבודות היום" description="עבודות שנקבעו להיום יופיעו כאן." />
            ) : (
              <div className={styles.list}>
                {sections.today.map((order) => (
                  <OrderRow key={order.id} order={order} />
                ))}
              </div>
            )}
          </section>

          <section>
            <p className={styles.sectionTitle}>עבודות עתידיות</p>
            {sections.upcoming.length === 0 ? (
              <EmptyState title="אין עדיין עבודות עתידיות" description="עבודות שנקבעו לימים הבאים יופיעו כאן." />
            ) : (
              <div className={styles.list}>
                {sections.upcoming.map((order) => (
                  <OrderRow key={order.id} order={order} />
                ))}
              </div>
            )}
          </section>

          <section>
            <p className={styles.sectionTitle}>היסטוריה</p>
            {sections.history.length === 0 ? (
              <EmptyState title="אין עדיין היסטוריית עבודות" description="עבודות שהושלמו, בוטלו או פג תוקפן יופיעו כאן." />
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
