import { useEffect, useState } from 'react';
import { useNavigate, useParams, useLocation, Link } from 'react-router-dom';
import { PageHeader, Card, Button, StatusBadge } from '../../shared/components';
import { useAuth, useOrderStatus, useEtaCountdown } from '../../shared/hooks';
import { cancelOrder, markOnTheWay, completeOrder, getIssue, getCategoryNameHe, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { OrderStatus, IssueDetailResponse } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './OrderTrackingPage.module.css';

const CUSTOMER_CANCELLABLE_STATUSES: OrderStatus[] = ['PENDING', 'CONFIRMED', 'ON_THE_WAY'];

const ORDER_ACTION_ERROR_MESSAGES: Record<string, string> = {
  ORDER_NOT_CANCELLABLE: 'לא ניתן לבטל את ההזמנה הזו כרגע.',
  ORDER_NOT_CONFIRMED: 'לא ניתן לסמן את ההזמנה כ’בדרך’ כרגע.',
  ORDER_NOT_ON_THE_WAY: 'לא ניתן לסמן את ההזמנה כהושלמה כרגע.',
};

/** Router-state shape passed by `WeeklyCalendarGrid`'s `BOOKED`-segment click-through (design
 *  §7.3/§43): the calendar week that was visible when the professional clicked into this
 *  order, so the back button can return to that exact week instead of resetting to "today". */
interface TrackingLocationState {
  returnTo?: { weekStart: string };
}

/**
 * `/orders/:orderId` — the tracking screen (either party, ownership enforced server-side).
 * Status-only real-time updates via short-polling (`useOrderStatus`, `overview.md` §3.3) —
 * no GPS/map. `expectedArrivalAt` IS a real, persisted field on `OrderDetailResponse` as of
 * `active-booking-floating-indicator.md` (supersedes the prior "ETA never persisted / no
 * new field here" ruling, see that doc's §0.1) — rendered below as a live countdown while
 * `orderStatus === 'ON_THE_WAY'`, via the same `useEtaCountdown` hook the floating
 * indicator uses.
 *
 * **Professional weekly availability calendar design §7.5, M5 additions**: a one-shot `GET
 * /api/issues/{issueId}` fetch (once `order` resolves) sources category/description/urgency/
 * photos — the same `IncomingRequestCard.tsx` pattern, no new backend endpoint. The
 * counterparty-name bug is fixed (shows `customerName` for a `PROFESSIONAL` viewer,
 * `professionalName` for a `CUSTOMER` viewer — previously always showed `professionalName`
 * regardless of role). `order.id`/`order.bookedEnd` are now rendered (both already on the DTO).
 * `order.customerPhone` renders for a `PROFESSIONAL` viewer only (server-scoped to a party of
 * the order, `PENDING` onward — no extra client gating needed beyond the role check, since this
 * screen only ever loads an order the caller is already a party to). The back button reads
 * `location.state?.returnTo` (set only by a booked-block click-through from the calendar) and,
 * when present, returns to that exact week instead of this screen's normal role-based
 * `backPath` — every other entry point into this screen is unaffected.
 */
export default function OrderTrackingPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useAuth();
  const { orderId: orderIdParam } = useParams<{ orderId: string }>();
  const orderId = Number(orderIdParam);

  const { order, error, isLoading, refetch } = useOrderStatus(orderId);
  const [issue, setIssue] = useState<IssueDetailResponse | undefined>(undefined);
  const [isCancelling, setIsCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [isUpdatingStatus, setIsUpdatingStatus] = useState(false);
  const [statusActionError, setStatusActionError] = useState<string | null>(null);

  const { remainingMinutes, isArriving } = useEtaCountdown(
    order?.orderStatus === 'ON_THE_WAY' ? order.expectedArrivalAt : null,
  );

  // One-shot issue fetch (category/description/urgency/photos, design §7.5 point 1) once the
  // order resolves — keyed on `issueId` alone (not the whole `order` object, which gets a new
  // identity on every poll tick) so this never re-fetches an issue that hasn't changed.
  const issueId = order?.issueId;
  useEffect(() => {
    if (!issueId) {
      return;
    }
    let cancelled = false;
    getIssue(issueId)
      .then((result) => {
        if (!cancelled) {
          setIssue(result);
        }
      })
      .catch(() => {
        // Best-effort enrichment — a failed issue fetch shouldn't block the rest of the
        // tracking screen (order status/actions still render from `order` alone).
      });
    return () => {
      cancelled = true;
    };
  }, [issueId]);

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

  // §43: a booked-block click-through from the calendar carries the visible week via router
  // state — the back button returns there instead of this screen's normal role-based default.
  const returnTo = (location.state as TrackingLocationState | null)?.returnTo;
  const backPath = returnTo
    ? `/pro/availability?week=${returnTo.weekStart}`
    : user?.role === 'PROFESSIONAL'
      ? '/pro'
      : '/orders';

  const isProfessionalViewer = user?.role === 'PROFESSIONAL';
  const counterpartyName = order ? (isProfessionalViewer ? order.customerName : order.professionalName) : '';

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
              <div>
                <p className={styles.professionalName}>{counterpartyName}</p>
                <p className={styles.orderIdLabel}>הזמנה #{order.id}</p>
              </div>
              <StatusBadge status={order.orderStatus} />
            </div>

            {order.orderStatus === 'ON_THE_WAY' && remainingMinutes !== null && (
              <div className={styles.row}>
                <span className={styles.rowLabel}>זמן הגעה משוער</span>
                <span className={styles.rowValue}>{isArriving ? 'מגיע/ה עכשיו' : `כ־${remainingMinutes} דקות`}</span>
              </div>
            )}

            <hr className={styles.divider} />

            <div className={styles.headerRow}>
              {issue ? (
                <span className={styles.category}>{getCategoryNameHe(issue.categoryId)}</span>
              ) : (
                <span className={styles.skeleton} />
              )}
              {issue?.urgencyType === 'SOS' && <span className={styles.sosTag}>SOS</span>}
            </div>

            {issue && <p className={styles.description}>“{issue.description}”</p>}

            {issue && issue.images.length > 0 && (
              <div className={styles.photoRow}>
                {issue.images.map((image) => (
                  <div key={image.id} className={styles.photoThumbWrapper}>
                    <img src={image.imageUrl} alt="" className={styles.photoThumb} />
                  </div>
                ))}
              </div>
            )}

            <hr className={styles.divider} />

            <div className={styles.row}>
              <span className={styles.rowLabel}>תאריך ושעה</span>
              <span className={styles.rowValue}>
                {formatDateLabel(order.bookedStart)}, {formatTimeLabel(order.bookedStart)}
                {order.bookedEnd ? `–${formatTimeLabel(order.bookedEnd)}` : ''}
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

            {isProfessionalViewer && order.customerPhone && (
              <div className={styles.row}>
                <span className={styles.rowLabel}>טלפון הלקוח</span>
                <span className={styles.rowValue}>{order.customerPhone}</span>
              </div>
            )}

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
