import { createContext } from 'react';

/**
 * MS6 Professional Command Center design doc §3.3. Mirrors `activeOrderContext.ts`'s shape
 * (context + non-component values kept in their own file for the Fast-Refresh lint reason
 * `authContext.ts` established). No selection-algorithm helpers needed here (unlike
 * `activeOrderContext.ts`) — this context only ever needs a raw pending-order count.
 */
export interface PendingRequestsContextValue {
  /** `orders.length` from the caller's own `GET /api/bookings/orders/me?status=PENDING`. */
  count: number;
  /** Forces an immediate re-poll (mirrors `usePolling`'s own `refetch`). */
  refetch: () => void;
}

export const PendingRequestsContext = createContext<PendingRequestsContextValue | undefined>(undefined);
