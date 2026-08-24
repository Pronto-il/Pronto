import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { usePolling } from './usePolling';
import { getMyOrders } from '../api/bookings';
import type { MyOrdersResponse } from '../api/bookings';
import { MY_PENDING_ORDERS_KEY } from '../api/resourceKeys';
import { PendingRequestsContext } from './pendingRequestsContext';

/** Background cadence: enough for a count badge, matching `WeeklyCalendarGrid`'s own coarse
 *  calendar cadence (MS6 design doc §3.3). */
const BACKGROUND_POLL_INTERVAL_MS = 25_000;
/** Cadence while the feed screen itself is on screen — accept/reject is a live action there. */
const LIVE_POLL_INTERVAL_MS = 6_000;

/**
 * Holds the professional's own pending requests in React context, polling
 * `GET /api/bookings/orders/me?status=PENDING` via `usePolling` (MS6 design doc §3.3).
 * Mirrors `ActiveOrderProvider.tsx`'s shape, but deliberately scoped narrower: mounted inside
 * `ProDashboardLayout.tsx` (wrapping `<Outlet />`), not in `App.tsx` — this data has no reason
 * to poll outside the `/pro/*` subtree.
 *
 * <h2>One poll, three consumers</h2>
 *
 * The sidebar badge (`ProDashboardLayout`), the command-center banner (`CommandCenterBanner`)
 * and the feed itself (`IncomingRequestsPage`) all read this. The feed used to run its own
 * independent 5s poll of the identical URL — documented at the time as an accepted
 * redundancy — which meant `/pro/requests` asked the same question twice on two unrelated
 * timers. It now consumes `orders` from here, so there is exactly one request in flight for this
 * resource no matter how many consumers are mounted.
 *
 * <h2>Consumers raise the cadence, they don't fork it</h2>
 *
 * A screen that needs the feed *live* calls `setLiveCadence(true)` for as long as it is mounted
 * (`useLivePendingRequests` does this), which moves the shared poll to 6s and back to 25s on
 * unmount. Ref-counted, so two live consumers can't leave the fast cadence stuck on when one of
 * them unmounts. This is what makes "the badge is coarse, the feed is live" true without it
 * being two separate polls.
 */
export function PendingRequestsProvider({ children }: { children: ReactNode }) {
  const [liveConsumers, setLiveConsumers] = useState(0);

  const { data, error, isLoading, refetch } = usePolling<MyOrdersResponse>(() => getMyOrders('PENDING'), {
    key: MY_PENDING_ORDERS_KEY,
    intervalMs: liveConsumers > 0 ? LIVE_POLL_INTERVAL_MS : BACKGROUND_POLL_INTERVAL_MS,
  });

  const setLiveCadence = useCallback((isLive: boolean) => {
    setLiveConsumers((previous) => Math.max(0, previous + (isLive ? 1 : -1)));
  }, []);

  const orders = useMemo(() => data?.orders ?? [], [data]);

  const value = useMemo(
    () => ({ orders, count: orders.length, isLoading, error, refetch, setLiveCadence }),
    [orders, isLoading, error, refetch, setLiveCadence],
  );

  return <PendingRequestsContext.Provider value={value}>{children}</PendingRequestsContext.Provider>;
}
