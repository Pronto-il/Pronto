import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronLeft } from 'lucide-react';
import { StatusBadge, Button, EmptyState, Skeleton } from '../../shared/components';
import { ProfessionalProfileModal } from '../professionals';
import { useEtaCountdown, usePolling } from '../../shared/hooks';
import { getMyOrders, GENERIC_ERROR_MESSAGE, MY_ORDERS_KEY } from '../../shared/api';
import type { MyOrdersResponse, OrderSummary, OrderStatus } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './MyOrdersPage.module.css';

/**
 * What "History" means on a customer-facing screen: an order that actually happened, or one
 * somebody deliberately called off.
 *
 * <p>This deliberately **narrowed** in the MS1 finalization pass. It previously also carried
 * `REJECTED` and `EXPIRED` — the MS4 reasoning being that both were already visible before that
 * milestone added sectioning, so folding them in removed nothing. That reasoning was about not
 * regressing a list; it was never an argument that they belong in a customer's history. They do
 * not: an order that timed out with no professional response, or that a professional declined, is
 * a record of the platform failing to find someone, not of a service the customer received. See
 * {@link HIDDEN_STATUSES}.
 */
const HISTORY_STATUSES: readonly OrderStatus[] = ['COMPLETED', 'CANCELLED'];

/**
 * Terminal statuses excluded from this screen altogether — **presentation only**. The rows are
 * untouched server-side: `getMyOrders()` still returns them, nothing is deleted, and no backend
 * filter was added.
 *
 * <p>They must be named explicitly rather than left to fall through, because the bucketing below
 * is an if/else: anything not recognised as History lands in **Active**, and an expired order
 * displayed under פעילות וקרובות is a worse bug than the one this change fixes.
 *
 * <p>`EXPIRED` is the expiry/time-out/no-response flow this pass was asked to hide. `REJECTED` —
 * a professional declining — is excluded on the same principle: it is terminal, so it can never be
 * Active, and it is not a service the customer received, so it is not History either.
 */
const HIDDEN_STATUSES: readonly OrderStatus[] = ['REJECTED', 'EXPIRED'];

interface OrderSections {
  active: OrderSummary[];
  history: OrderSummary[];
}

/**
 * Pure client-side bucketing over the page's already-fetched, unfiltered `OrderSummary[]` — no
 * new endpoint, no change to `getMyOrders()` (MS4 design doc §4 Q1). Unlike the professional
 * side's `MyJobsPage.tsx` (three sections: today/upcoming/history), this page uses exactly two
 * *rendered* sections — everything not in `HISTORY_STATUSES` or `HIDDEN_STATUSES` is a single
 * combined "Active/Upcoming" bucket, sorted soonest-first.
 *
 * This affects only what this screen lists. The active-order surfaces elsewhere in the app
 * (`useActiveOrder`, `ActiveOrderIndicator`, `/orders/:id` tracking) read the API directly and
 * are untouched — a hidden order is still reachable at its own URL.
 */
function bucketOrders(orders: OrderSummary[]): OrderSections {
  const active: OrderSummary[] = [];
  const history: OrderSummary[] = [];

  for (const order of orders) {
    if (HIDDEN_STATUSES.includes(order.orderStatus)) {
      continue;
    }
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

function initials(fullName: string): string {
  const parts = fullName.trim().split(/\s+/);
  const first = parts[0]?.[0] ?? '';
  const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (first + last).toUpperCase();
}

function OrderRow({
  order,
  onOpenProfessional,
}: {
  order: OrderSummary;
  onOpenProfessional: (professionalId: number) => void;
}) {
  // MS5 §3.H: the one figure a customer scanning this list actually wants is how far away a
  // professional already on the way is. `expectedArrivalAt` is already on `OrderSummary`, so
  // this needs no extra request — same hook the tracking screen and the floating indicator use.
  const { remainingMinutes, isArriving } = useEtaCountdown(
    order.orderStatus === 'ON_THE_WAY' ? order.expectedArrivalAt : null,
  );

  return (
    // A wrapper, not a bigger link: the professional's identity below is its own control, and an
    // interactive element cannot be nested inside an `<a>`. The order link keeps its exact former
    // content, so tapping anywhere on the date/price/status still opens tracking.
    <div className={styles.rowGroup}>
      <Link to={`/orders/${order.id}`} className={styles.row}>
        <div className={styles.rowMain}>
          <span className={styles.rowDate}>
            {formatDateLabel(order.bookedStart)}, {formatTimeLabel(order.bookedStart)}
          </span>
          <span className={styles.rowPrice}>₪{order.finalPrice}</span>
        </div>
        <div className={styles.rowSide}>
          <StatusBadge status={order.orderStatus} />
          {remainingMinutes !== null && (
            <span className={styles.rowEta}>{isArriving ? 'מגיע/ה עכשיו' : `בעוד ${remainingMinutes} דק׳`}</span>
          )}
        </div>
      </Link>

      {/* Opens the profile in place (`ProfessionalProfileModal`) rather than navigating — a
          customer checking who a past order was with shouldn't lose their place in the list.
          Rendered only when the name actually resolved; nothing is invented for a missing one. */}
      {order.professionalName && (
        <button
          type="button"
          className={styles.professionalButton}
          onClick={() => onOpenProfessional(order.professionalId)}
          aria-label={`צפייה בפרופיל של ${order.professionalName}`}
        >
          <span className={styles.professionalAvatar} aria-hidden="true">
            {initials(order.professionalName)}
          </span>
          <span className={styles.professionalName}>{order.professionalName}</span>
          <ChevronLeft size={16} aria-hidden="true" className={styles.professionalChevron} />
        </button>
      )}
    </div>
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
  /** The professional whose profile is open in the in-page modal, if any. */
  const [openProfessionalId, setOpenProfessionalId] = useState<number | null>(null);

  // Reads the same `GET /api/bookings/orders/me` entry `ActiveOrderProvider` already keeps warm
  // for this session, so arriving here usually costs no request at all — and never costs a
  // second one alongside the provider's. `enabled: false` because the provider owns the cadence;
  // this screen only needs the current answer when it opens.
  //
  // `maxStaleOnMountMs` is what keeps that from becoming a stale-state regression: the provider
  // drops to a 60s cadence when nothing is live, and a list of the customer's own orders should
  // not be a minute old the moment they open it. Fresher than 15s renders from cache; older than
  // that costs exactly the one request this screen used to make unconditionally.
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
    <div className="focused-page">
      {/* No page title: "ההזמנות שלי" repeated the nav label of the link that got you here
          verbatim — it is in the desktop nav and in `BottomNav` on mobile, so the current screen
          is already marked `aria-current="page"` there. The two section headings
          (פעילות וקרובות / היסטוריה) carry the structure this screen actually needs. */}
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

      {/* Gated on what this screen actually *shows*, not on the raw fetch: a customer whose only
          orders are hidden (expired/rejected) has, as far as this screen is concerned, no orders
          — and the honest response to that is the same "get started" empty state a brand-new
          customer sees, not two sections that are each separately empty. */}
      {orders !== null && sections.active.length + sections.history.length === 0 && (
        <EmptyState
          title="אין עדיין הזמנות"
          description="כשאתם מזמינים בעל מקצוע, ההזמנות שלכם יופיעו כאן."
          action={<Button onClick={() => navigate('/issues/new')}>יש לי תקלה</Button>}
        />
      )}

      {orders !== null && sections.active.length + sections.history.length > 0 && (
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
                  <OrderRow key={order.id} order={order} onOpenProfessional={setOpenProfessionalId} />
                ))}
              </div>
            )}
          </section>

          <section>
            <p className={styles.sectionTitle}>היסטוריה</p>
            {sections.history.length === 0 ? (
              <EmptyState
                title="אין עדיין היסטוריית הזמנות"
                description="הזמנות שהושלמו או בוטלו יופיעו כאן."
              />
            ) : (
              <div className={styles.list}>
                {sections.history.map((order) => (
                  <OrderRow key={order.id} order={order} onOpenProfessional={setOpenProfessionalId} />
                ))}
              </div>
            )}
          </section>
        </div>
      )}

      <ProfessionalProfileModal
        professionalId={openProfessionalId}
        isOpen={openProfessionalId !== null}
        onClose={() => setOpenProfessionalId(null)}
      />
    </div>
  );
}
