import { useEffect, useState } from 'react';
import { useNavigate, useParams, useLocation } from 'react-router-dom';
import { PageHeader, Card, Button, StatusBadge, Skeleton, Modal } from '../../shared/components';
import { OrderStatusHero } from './OrderStatusHero';
import { OrderProgressStepper } from './OrderProgressStepper';
import { ProntoAnalysisCard } from './ProntoAnalysisCard';
import { useAuth, useOrderStatus, useEtaCountdown, useActiveOrder } from '../../shared/hooks';
import {
  cancelOrder,
  markOnTheWay,
  completeOrder,
  getIssue,
  getCategoryNameHe,
  getProfessionalProfile,
  ApiError,
  GENERIC_ERROR_MESSAGE,
} from '../../shared/api';
import type { OrderStatus, IssueDetailResponse, ProfessionalProfileResponse } from '../../shared/api';
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
  const { acknowledgeOrder } = useActiveOrder();
  const [issue, setIssue] = useState<IssueDetailResponse | undefined>(undefined);
  const [professional, setProfessional] = useState<ProfessionalProfileResponse | null>(null);
  const [isCancelling, setIsCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [isUpdatingStatus, setIsUpdatingStatus] = useState(false);
  const [statusActionError, setStatusActionError] = useState<string | null>(null);
  /** MS5 §3.C — cancelling is irreversible and was previously a one-click action. */
  const [isCancelConfirmOpen, setIsCancelConfirmOpen] = useState(false);

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

  // The assigned professional's public profile, for the customer's status hero (who am I waiting
  // for?). Keyed on `professionalId` alone — like the issue fetch above — so the status poll's
  // new `order` identity every few seconds never re-triggers it. `GET /api/professionals/{id}` is
  // an existing either-role endpoint; nothing new was added for this. Best-effort: a failure
  // leaves `professional` null and the hero falls back to its name-only headline.
  const professionalId = order?.professionalId;
  const isCustomerViewer = user?.role === 'CUSTOMER';
  useEffect(() => {
    if (!professionalId || !isCustomerViewer) {
      return;
    }
    let cancelled = false;
    getProfessionalProfile(professionalId)
      .then((result) => {
        if (!cancelled) {
          setProfessional(result);
        }
      })
      .catch(() => {
        // Non-blocking enrichment — see above.
      });
    return () => {
      cancelled = true;
    };
  }, [professionalId, isCustomerViewer]);

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
      // Closed on both paths: the error banner lives on the page, not in the dialog, so
      // leaving the dialog up on failure would hide the explanation behind it.
      setIsCancelConfirmOpen(false);
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

  // MS5 (design doc §4 Q2): the §79 status-led treatment is the customer's screen only. The
  // professional keeps the details-card view MS6's surfaces already hand off to, including
  // its customer-phone row — which §4 Q1 confirms is deliberately one-directional.
  const showCustomerHero = !isProfessionalViewer && order !== undefined && order !== null;
  const bookedLabel = order
    ? `${formatDateLabel(order.bookedStart)} בשעה ${formatTimeLabel(order.bookedStart)}`
    : '';

  /**
   * Terminal-state next step (§3.D).
   *
   * <p>`CANCELLED`, `REJECTED` and — as of the SOS final-readiness pass — `EXPIRED` all now lead
   * to the same place: back into professional selection **for the same issue**. All three run a
   * server-side transition that returns `issues.status` to `OPEN` (`revertToOpen` for the first
   * two, `reopenIfBooked` for expiry), so the issue is genuinely bookable again. Verified in
   * `BookingsService`/`IssueRepository`, not assumed — a CTA into a dead issue would be worse
   * than none.
   *
   * <p>`EXPIRED` used to send the customer to `/issues/new`, which threw away a description,
   * photos and an AI classification they had already provided and made them redo all of it
   * because a professional failed to answer in time. `order.issueId` is on the DTO already, so
   * the recovery target is a plain URL that survives a refresh — no router state, no re-created
   * issue, no second trip through classification.
   *
   * <p>The SOS branch points at `/issues/{issueId}/sos-booking`, which now renders `features/sos`'s
   * real Pronto SOS entry page — so this recovery CTA lands a customer straight back into an
   * urgent search on the same issue, which is exactly what it should do. (Historical orders from
   * the removed legacy SOS flow reach it too, and are equally well served.)
   */
  function renderTerminalAction() {
    if (!order || isProfessionalViewer) {
      return undefined;
    }
    const isRecoverable =
      order.orderStatus === 'CANCELLED' || order.orderStatus === 'REJECTED' || order.orderStatus === 'EXPIRED';
    if (!isRecoverable) {
      return undefined;
    }
    const path = issue?.urgencyType === 'SOS'
      ? `/issues/${order.issueId}/sos-booking`
      : `/issues/${order.issueId}/booking`;
    return (
      <Button onClick={() => navigate(path)} fullWidth>
        בחירת בעל מקצוע אחר
      </Button>
    );
  }

  function renderHeroAction() {
    if (canReview) {
      return (
        <>
          <Button onClick={() => navigate(`/orders/${orderId}/review`)} fullWidth>
            השארת ביקורת
          </Button>
          {/* "לא עכשיו" now actually dismisses: it acknowledges the order, so the floating review
              prompt stops offering this one too, instead of navigating away and leaving the
              bubble on screen. */}
          <Button
            variant="ghost"
            onClick={() => {
              acknowledgeOrder(orderId);
              navigate('/orders');
            }}
            fullWidth
          >
            לא עכשיו
          </Button>
        </>
      );
    }
    return renderTerminalAction();
  }

  return (
    <div className="focused-page">
      <PageHeader title="מעקב הזמנה" onBack={() => navigate(backPath)} />

      {isLoading && !order && <Skeleton variant="rect" className={styles.loadingCard} />}

      {!order && !isLoading && error && (
        <div className={styles.banner} role="alert">
          <p>לא הצלחנו לטעון את ההזמנה. אפשר לנסות שוב בעוד רגע.</p>
        </div>
      )}

      {order && (
        <div className={styles.wrapper}>
          {showCustomerHero && (
            <>
              <OrderStatusHero
                status={order.orderStatus}
                professionalName={order.professionalName}
                remainingMinutes={remainingMinutes}
                isArriving={isArriving}
                bookedLabel={bookedLabel}
                professional={professional}
                action={renderHeroAction()}
              />
              <OrderProgressStepper status={order.orderStatus} />
            </>
          )}

          <Card className={styles.statusCard}>
            <div className={styles.statusRow}>
              <div>
                <p className={styles.professionalName}>{counterpartyName}</p>
                <p className={styles.orderIdLabel}>הזמנה #{order.id}</p>
              </div>
              <StatusBadge status={order.orderStatus} />
            </div>

            {/* The customer's ETA now leads the hero above; the professional's view keeps the
                inline row, since that view is unchanged this milestone (§4 Q2). */}
            {isProfessionalViewer && order.orderStatus === 'ON_THE_WAY' && remainingMinutes !== null && (
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
                <Skeleton variant="rect" radius="var(--radius-sm)" className={styles.categorySkeleton} />
              )}
              {issue?.urgencyType === 'SOS' && <span className={styles.sosTag}>SOS</span>}
            </div>

            {/* Labelled only for the professional: on the customer's own screen "מה הלקוח
                תיאר" would be redundant, but on the professional's it is what keeps the
                customer's words distinguishable from the Pronto analysis card below. */}
            {issue && isProfessionalViewer && <p className={styles.reportLabel}>מה הלקוח תיאר</p>}
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

          {/* `prontoAnalysis` is server-scoped to a professional with an order on the issue, so
              it is simply absent for a customer — no client-side role gate needed beyond this
              null check. Absent also covers issues created before briefs existed. */}
          {issue?.prontoAnalysis && (
            <ProntoAnalysisCard analysis={issue.prontoAnalysis} clarifications={issue.clarifications} />
          )}

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

          {/* Demoted from a full-width destructive button (§3.C): cancelling is rare and
              irreversible, and it should not be the loudest thing on a screen whose subject
              is the status. The confirm dialog's own action stays destructive-styled. */}
          {canCancel && (
            <Button variant="ghost" onClick={() => setIsCancelConfirmOpen(true)} fullWidth>
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

          {/* The review CTA moved into the completed hero (§3.E) — it used to be a plain text
              link at the bottom of the page, below the fold on mobile, for the one action the
              product most wants at that moment. */}
        </div>
      )}

      <Modal
        isOpen={isCancelConfirmOpen}
        onClose={() => setIsCancelConfirmOpen(false)}
        title="לבטל את ההזמנה?"
        size="normal"
        footer={
          <div className={styles.confirmActions}>
            <Button variant="destructive" onClick={handleCancel} loading={isCancelling} fullWidth>
              ביטול ההזמנה
            </Button>
            <Button variant="secondary" onClick={() => setIsCancelConfirmOpen(false)} fullWidth>
              חזרה
            </Button>
          </div>
        }
      >
        <p className={styles.confirmText}>
          הפעולה הזו סופית. המועד שנשמר עבורך ישוחרר, והתקלה שלך תחזור להיות פתוחה כדי שאפשר יהיה
          לבחור בעל מקצוע אחר.
        </p>
      </Modal>
    </div>
  );
}
