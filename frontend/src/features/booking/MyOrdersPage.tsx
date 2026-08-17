import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { PageHeader, StatusBadge, Button } from '../../shared/components';
import { getMyOrders, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { OrderSummary } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './MyOrdersPage.module.css';

/**
 * `/orders` — simple list of the caller's own orders, `CUSTOMER`-only per this milestone's
 * route map (the professional side has no equivalent screen yet — its own request/job
 * history is out of `features/dashboard`'s scope this pass). Each row links to
 * `/orders/:id` for the full tracking view. Empty state per DESIGN_SYSTEM.md §60/
 * FRONTEND_AGENT.md §25.
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

  return (
    <div className="focused-page">
      <PageHeader title="ההזמנות שלי" />

      {error && (
        <div className={styles.banner} role="alert">
          <p>{error}</p>
        </div>
      )}

      {!error && orders === null && <p>טוען…</p>}

      {orders !== null && orders.length === 0 && (
        <div className={styles.empty}>
          <p className={styles.emptyTitle}>אין עדיין הזמנות</p>
          <p className={styles.emptyText}>כשאתם מזמינים בעל מקצוע, ההזמנות שלכם יופיעו כאן.</p>
          <Button onClick={() => navigate('/issues/new')}>יש לי תקלה</Button>
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
