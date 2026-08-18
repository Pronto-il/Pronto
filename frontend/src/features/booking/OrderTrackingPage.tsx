import { useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import { PageHeader, Card, Button, StatusBadge } from '../../shared/components';
import { useAuth, useOrderStatus, useEtaCountdown } from '../../shared/hooks';
import { cancelOrder, markOnTheWay, completeOrder, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { OrderStatus } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './OrderTrackingPage.module.css';

const CUSTOMER_CANCELLABLE_STATUSES: OrderStatus[] = ['PENDING', 'CONFIRMED', 'ON_THE_WAY'];

const ORDER_ACTION_ERROR_MESSAGES: Record<string, string> = {
  ORDER_NOT_CANCELLABLE: 'לא ניתן לבטל את ההזמנה הזו כרגע.',
  ORDER_NOT_CONFIRMED: 'לא ניתן לסמן את ההזמנה כ’בדרך’ כרגע.',
  ORDER_NOT_ON_THE_WAY: 'לא ניתן לסמן את ההזמנה כהושלמה כרגע.',
};

/**
 * `/orders/:orderId` — the tracking screen (either party, ownership enforced server-side).
 * Status-only real-time updates via short-polling (`useOrderStatus`, `overview.md` §3.3) —
 * no GPS/map. `expectedArrivalAt` IS a real, persisted field on `OrderDetailResponse` as of
 * `active-booking-floating-indicator.md` (supersedes the prior "ETA never persisted / no
 * new field here" ruling, see that doc's §0.1) — rendered below as a live countdown while
 * `orderStatus === 'ON_THE_WAY'`, via the same `useEtaCountdown` hook the floating
 * indicator uses.
 */
export default function OrderTrackingPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const { orderId: orderIdParam } = useParams<{ orderId: string }>();
  const orderId = Number(orderIdParam);

  const { order, error, isLoading, refetch } = useOrderStatus(orderId);
  const [isCancelling, setIsCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [isUpdatingStatus, setIsUpdatingStatus] = useState(false);
  const [statusActionError, setStatusActionError] = useState<string | null>(null);

  const { remainingMinutes, isArriving } = useEtaCountdown(
    order?.orderStatus === 'ON_THE_WAY' ? order.expectedArrivalAt : null,
  );

  async function handleCancel() {
    setCancelError(null);
    setIsCancelling(true);
    try {
      await cancelOrder(orderId);
      refetch();
    } catch (err) {
      if (err instanceof ApiError && ORDER_ACTION_ERROR_MESSAGES[err.code]) {
        setCancelError(ORDER_ACTION_ERROR_MESSAGES[err.code]);
      } else {
        setCancelError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsCancelling(false);
    }
  }

  async function handleMarkOnTheWay() {
    setStatusActionError(null);
    setIsUpdatingStatus(true);
    try {
      await markOnTheWay(orderId);
      refetch();
    } catch (err) {
      if (err instanceof ApiError && ORDER_ACTION_ERROR_MESSAGES[err.code]) {
        setStatusActionError(ORDER_ACTION_ERROR_MESSAGES[err.code]);
      } else {
        setStatusActionError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsUpdatingStatus(false);
    }
  }

  async function handleComplete() {
    setStatusActionError(null);
    setIsUpdatingStatus(true);
    try {
      await completeOrder(orderId);
      refetch();
    } catch (err) {
      if (err instanceof ApiError && ORDER_ACTION_ERROR_MESSAGES[err.code]) {
        setStatusActionError(ORDER_ACTION_ERROR_MESSAGES[err.code]);
      } else {
        setStatusActionError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsUpdatingStatus(false);
    }
  }

  const canCancel = user?.role === 'CUSTOMER' && order && CUSTOMER_CANCELLABLE_STATUSES.includes(order.orderStatus);
  const canMarkOnTheWay = user?.role === 'PROFESSIONAL' && order?.orderStatus === 'CONFIRMED';
  const canComplete = user?.role === 'PROFESSIONAL' && order?.orderStatus === 'ON_THE_WAY';
  const canReview = user?.role === 'CUSTOMER' && order?.orderStatus === 'COMPLETED';
  const backPath = user?.role === 'PROFESSIONAL' ? '/pro' : '/orders';

  return (
    <div className="focused-page">
      <PageHeader title="מעקב הזמנה" onBack={() => navigate(backPath)} />

      {isLoading && !order && <p>טוען…</p>}

      {!order && !isLoading && error && (
        <div className={styles.banner} role="alert">
          <p>לא הצלחנו לטעון את ההזמנה. אפשר לנסות שוב בעוד רגע.</p>
        </div>
      )}

      {order && (
        <div className={styles.wrapper}>
          <Card className={styles.statusCard}>
            <div className={styles.statusRow}>
              <p className={styles.professionalName}>{order.professionalName}</p>
              <StatusBadge status={order.orderStatus} />
            </div>

            {order.orderStatus === 'ON_THE_WAY' && remainingMinutes !== null && (
              <div className={styles.row}>
                <span className={styles.rowLabel}>זמן הגעה משוער</span>
                <span className={styles.rowValue}>{isArriving ? 'מגיע/ה עכשיו' : `כ־${remainingMinutes} דקות`}</span>
              </div>
            )}

            <hr className={styles.divider} />

            <div className={styles.row}>
              <span className={styles.rowLabel}>תאריך ושעה</span>
              <span className={styles.rowValue}>
                {formatDateLabel(order.bookedStart)}, {formatTimeLabel(order.bookedStart)}
              </span>
            </div>

            <div className={styles.row}>
              <span className={styles.rowLabel}>כתובת</span>
              <span className={styles.rowValue}>
                {order.serviceCity}, {order.serviceStreet} {order.serviceHouseNumber}
                {order.serviceApartment ? `, דירה ${order.serviceApartment}` : ''}
                {order.serviceFloor ? `, קומה ${order.serviceFloor}` : ''}
                {order.serviceEntrance ? `, כניסה ${order.serviceEntrance}` : ''}
              </span>
              {order.serviceAddressNotes && <span className={styles.rowValue}>{order.serviceAddressNotes}</span>}
            </div>

            <hr className={styles.divider} />

            <div className={styles.totalRow}>
              <span className={styles.totalLabel}>סה״כ</span>
              <span className={styles.totalPrice}>₪{order.finalPrice}</span>
            </div>
          </Card>

          {cancelError && (
            <div className={styles.banner} role="alert">
              <p>{cancelError}</p>
            </div>
          )}

          {statusActionError && (
            <div className={styles.banner} role="alert">
              <p>{statusActionError}</p>
            </div>
          )}

          {canCancel && (
            <Button variant="destructive" onClick={handleCancel} loading={isCancelling} fullWidth>
              ביטול ההזמנה
            </Button>
          )}

          {canMarkOnTheWay && (
            <Button onClick={handleMarkOnTheWay} loading={isUpdatingStatus} fullWidth>
              יציאה לדרך
            </Button>
          )}

          {canComplete && (
            <Button onClick={handleComplete} loading={isUpdatingStatus} fullWidth>
              סיום העבודה
            </Button>
          )}

          {canReview && (
            <Link to={`/orders/${orderId}/review`} className={styles.reviewLink}>
              השאירו ביקורת
            </Link>
          )}
        </div>
      )}
    </div>
  );
}
