import type { ReactNode } from 'react';
import { usePolling } from './usePolling';
import { getMyOrders } from '../api/bookings';
import { PendingRequestsContext } from './pendingRequestsContext';

/** Matches `WeeklyCalendarGrid.tsx`'s own `CALENDAR_POLL_INTERVAL_MS` cadence (MS6 design doc
 *  §3.3) — a count badge doesn't need `IncomingRequestsPage`'s own 5s live-action cadence. */
const PENDING_REQUESTS_POLL_INTERVAL_MS = 25000;

/**
 * Holds the professional's own pending-request count in React context, polling
 * `GET /api/bookings/orders/me?status=PENDING` via `usePolling` (MS6 design doc §3.3).
 * Mirrors `ActiveOrderProvider.tsx`'s shape exactly, but deliberately scoped narrower:
 * mounted inside `ProDashboardLayout.tsx` (wrapping `<Outlet />`), not in `App.tsx` — this
 * data has no reason to poll outside the `/pro/*` subtree. Consumed by both the sidebar
 * badge (`ProDashboardLayout`) and the command-center banner (`CommandCenterBanner`).
 *
 * `IncomingRequestsPage`'s own independent 5s poll of the same endpoint is left completely
 * untouched — the resulting redundancy while `/pro/requests` is the active tab is an
 * accepted, minor N+1-style tradeoff at MVP scale (design doc §3.3).
 */
export function PendingRequestsProvider({ children }: { children: ReactNode }) {
  const { data, refetch } = usePolling(() => getMyOrders('PENDING'), {
    intervalMs: PENDING_REQUESTS_POLL_INTERVAL_MS,
  });

  const count = data?.orders.length ?? 0;

  return <PendingRequestsContext.Provider value={{ count, refetch }}>{children}</PendingRequestsContext.Provider>;
}
