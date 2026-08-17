import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { StatusBadge } from '../../shared/components';
import { getMyOrders, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { OrderSummary } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './MyJobsPage.module.css';

/**
 * "העבודות שלי" tab (`/pro/jobs`) — the professional's only way, besides typing an order
 * URL directly, to see a job again after it leaves the "בקשות חדשות" pending feed
 * (`IncomingRequestsPage`, `GET .../me?status=PENDING`). Calls `getMyOrders()` with no
 * status filter, mirroring `features/booking/MyOrdersPage.tsx`'s analogous customer-side
 * pattern (list everything the caller is party to, no client-side filtering) rather than
 * excluding `PENDING` — the pending feed is the action screen, this is the read-only
 * reference list, and duplication between the two is harmless.
 *
 * Read-only by design: no accept/reject/on-the-way/complete actions here — job-status
 * progression beyond accept/reject stays out of this milestone's scope.
 */
export default function MyJobsPage() {
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
        <div className={styles.list}>
          {orders.map((order) => (
            <Link key={order.id} to={`/orders/${order.id}`} className={styles.row}>
              <div className={styles.rowMain}>
                <span className={styles.rowDate}>
                  {formatDateLabel(order.bookedStart)}, {formatTimeLabel(order.bookedStart)}
                </span>
                <span className={styles.rowPrice}>₪{order.finalPrice}</span>
              </div>
              <StatusBadge status={order.orderStatus} />
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
