import { createContext } from 'react';
import type { OrderSummary } from '../api/bookings';

/**
 * MS6 Professional Command Center design doc §3.3. Mirrors `activeOrderContext.ts`'s shape
 * (context + non-component values kept in their own file for the Fast-Refresh lint reason
 * `authContext.ts` established).
 *
 * Carries the pending orders themselves, not just their count: `IncomingRequestsPage` renders
 * this exact list, and having it read the shared value is what removed its duplicate poll of the
 * same URL.
 */
export interface PendingRequestsContextValue {
  /** The caller's own `PENDING` orders, from `GET /api/bookings/orders/me?status=PENDING`. */
  orders: OrderSummary[];
  /** `orders.length` — the sidebar/banner badges want nothing else. */
  count: number;
  /** True until the first response settles. The badges ignore it; the feed screen renders it. */
  isLoading: boolean;
  /** Last failed read, or `null`. The last good `orders` are kept alongside it. */
  error: Error | null;
  /** Forces an immediate re-poll (mirrors `usePolling`'s own `refetch`). */
  refetch: () => void;
  /**
   * Ref-counted request for the live cadence, for as long as a consumer needs one. Call with
   * `true` on mount and `false` on unmount — or just use `useLivePendingRequests()`, which does
   * exactly that and is the only intended caller.
   */
  setLiveCadence: (isLive: boolean) => void;
}

export const PendingRequestsContext = createContext<PendingRequestsContextValue | undefined>(undefined);
