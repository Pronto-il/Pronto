import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useAuth } from './useAuth';
import { usePolling } from './usePolling';
import { getMyOrders } from '../api/bookings';
import { MY_ORDERS_KEY } from '../api/resourceKeys';
import { ActiveOrderContext, isLiveActiveOrder, selectActiveOrder } from './activeOrderContext';

const ACK_STORAGE_KEY = 'pronto_ack_completed_orders';

/**
 * Cadence by lifecycle, replacing the flat 4s this used to run at everywhere.
 *
 * The floating indicator is a summary of an order the customer already knows about, not the
 * screen they are watching it on (`OrderTrackingPage` is, and it polls the order's own detail
 * endpoint). What it actually has to be timely about is the ETA once somebody is on the way;
 * before that, "still waiting" and "confirmed" are states that change on a human timescale.
 */
const ON_THE_WAY_INTERVAL_MS = 10_000;
const PENDING_CONFIRMED_INTERVAL_MS = 20_000;
/** Nothing live — the poll is only there to notice that something started. */
const IDLE_INTERVAL_MS = 60_000;

interface StoredAck {
  ownerId: number;
  orderIds: number[];
}

function readStoredAck(): StoredAck | null {
  try {
    const raw = localStorage.getItem(ACK_STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as StoredAck;
    if (typeof parsed.ownerId !== 'number' || !Array.isArray(parsed.orderIds)) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

/**
 * Holds the currently-selected active order (for the floating indicator) in React context,
 * polling `GET /api/bookings/orders/me` via `usePolling` (§3 — list-poll only, no
 * per-order detail-poll). Nested inside `AuthProvider` in `App.tsx`, alongside
 * `BookingDraftProvider`, so it can call `useAuth()`.
 *
 * Also owns the acknowledged-completed-order-ids state (§6.1), including the same
 * cross-account-leakage guard `BookingDraftProvider` already uses for its own localStorage
 * key: a stored record that doesn't belong to the current session's user is cleared
 * outright, not merged/reconciled.
 *
 * <h2>It is now the session's answer to "is anything live right now"</h2>
 *
 * `hasLiveOrder` is this provider's second job, and the reason it matters is that other
 * subsystems hang off it rather than asking the server the same question again.
 * `useNotifications` is the first: notification state in this product only changes across an
 * order's lifecycle, so the bell polls while `hasLiveOrder` is true and not at all otherwise —
 * reading this context, with no request of its own, which is what keeps the rule from costing
 * exactly what it saves. A `COMPLETED` order awaiting its review prompt is deliberately *not*
 * live: the work is over, the prompt is a local UI state, and nothing further will arrive for it.
 *
 * The cadence is lifecycle-driven for the same reason (see the constants above). A customer
 * sitting on the home screen with nothing booked polls this once a minute; one with a
 * professional on the way polls every 10 seconds.
 */
export function ActiveOrderProvider({ children }: { children: ReactNode }) {
  const { user, isLoading: isAuthLoading } = useAuth();
  const [ack, setAck] = useState<StoredAck | null>(() => readStoredAck());

  useEffect(() => {
    if (isAuthLoading) {
      return;
    }
    if (!user || (ack && ack.ownerId !== user.id)) {
      setAck(null);
      localStorage.removeItem(ACK_STORAGE_KEY);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user, isAuthLoading]);

  const pollingEnabled = !isAuthLoading && user?.role === 'CUSTOMER';

  const acknowledgedOrderIds = ack && user && ack.ownerId === user.id ? ack.orderIds : [];

  // Read before the poll is configured so the cadence follows the state the last response
  // described — `data` is the same object identity between ticks, so this is not a render loop.
  const [intervalMs, setIntervalMs] = useState(IDLE_INTERVAL_MS);
  const { data, refetch } = usePolling(() => getMyOrders(), {
    key: MY_ORDERS_KEY,
    enabled: pollingEnabled,
    intervalMs,
  });

  const selection = useMemo(
    () => (data ? selectActiveOrder(data.orders, acknowledgedOrderIds) : null),
    // `acknowledgedOrderIds` is rebuilt each render from `ack`; depending on `ack` itself keeps
    // this memo stable between poll ticks that changed nothing.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [data, ack, user],
  );

  useEffect(() => {
    if (!selection) {
      setIntervalMs(IDLE_INTERVAL_MS);
      return;
    }
    setIntervalMs(
      selection.state === 'ON_THE_WAY'
        ? ON_THE_WAY_INTERVAL_MS
        : selection.state === 'PENDING_CONFIRMED'
          ? PENDING_CONFIRMED_INTERVAL_MS
          : IDLE_INTERVAL_MS,
    );
  }, [selection]);

  function acknowledgeOrder(orderId: number) {
    if (!user) {
      return;
    }
    setAck((prev) => {
      const base: StoredAck = prev && prev.ownerId === user.id ? prev : { ownerId: user.id, orderIds: [] };
      if (base.orderIds.includes(orderId)) {
        return base;
      }
      const next: StoredAck = { ownerId: user.id, orderIds: [...base.orderIds, orderId] };
      localStorage.setItem(ACK_STORAGE_KEY, JSON.stringify(next));
      return next;
    });
  }

  const value = useMemo(
    () => ({ selection, hasLiveOrder: isLiveActiveOrder(selection), acknowledgeOrder, refetch }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [selection, refetch, user],
  );

  return <ActiveOrderContext.Provider value={value}>{children}</ActiveOrderContext.Provider>;
}
