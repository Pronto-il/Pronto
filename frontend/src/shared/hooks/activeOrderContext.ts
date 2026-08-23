import { createContext } from 'react';
import type { OrderSummary } from '../api/bookings';

/**
 * Active-booking floating indicator (`active-booking-floating-indicator.md`). Mirrors the
 * shape/location of `bookingDraftContext.ts` — global, cross-feature client state consumed
 * both by the app shell (`AppLayout`'s `ActiveOrderIndicator`) and by
 * `features/booking/CompletionReviewPage`.
 */
export type ActiveOrderIndicatorState = 'PENDING_CONFIRMED' | 'ON_THE_WAY' | 'COMPLETED_UNACKNOWLEDGED';

export interface ActiveOrderSelection {
  order: OrderSummary;
  state: ActiveOrderIndicatorState;
}

/** Mirrors `BookingDraftContextValue`'s minimal shape. */
export interface ActiveOrderContextValue {
  selection: ActiveOrderSelection | null;
  /** Idempotent: a no-op if `orderId` is already acknowledged. See §6.2. */
  acknowledgeOrder: (orderId: number) => void;
  /** Forces an immediate re-poll of `GET /api/bookings/orders/me` (mirrors `usePolling`'s
   *  own `refetch`). */
  refetch: () => void;
}

export const ActiveOrderContext = createContext<ActiveOrderContextValue | undefined>(undefined);

/**
 * Decision 2's priority rule: ON_THE_WAY > CONFIRMED/PENDING > unacknowledged COMPLETED.
 * CANCELLED/REJECTED/EXPIRED are excluded from the candidate set entirely (§4). Tie-break
 * within a tier is this design's own recommendation (not specified by any source
 * document) -- flagged as such, easy to change: soonest-arriving first for ON_THE_WAY
 * (most useful to surface), most-recently-created first for PENDING/CONFIRMED, most-
 * recently-completed (updatedAt) first for COMPLETED_UNACKNOWLEDGED.
 *
 * **Review-prompt scope fix**: the COMPLETED tier now considers *only the single most recently
 * completed order*, and yields nothing when that one has been acknowledged. It used to filter the
 * acknowledged ones out first and then take the most recent of whatever remained, which meant
 * dismissing the latest visit promoted the one before it, then the one before that — the customer
 * was walked backwards through their history one prompt at a time. Asking about the visit that
 * just happened is useful; re-asking about a job from three months ago because it was never rated
 * is not, and that is the persistence being removed here.
 */
export function selectActiveOrder(
  orders: OrderSummary[],
  acknowledgedOrderIds: number[],
): ActiveOrderSelection | null {
  const onTheWay = orders.filter((o) => o.orderStatus === 'ON_THE_WAY');
  if (onTheWay.length > 0) {
    const soonest = [...onTheWay].sort((a, b) =>
      (a.expectedArrivalAt ?? '').localeCompare(b.expectedArrivalAt ?? ''),
    )[0];
    return { order: soonest, state: 'ON_THE_WAY' };
  }

  const pendingConfirmed = orders.filter((o) => o.orderStatus === 'PENDING' || o.orderStatus === 'CONFIRMED');
  if (pendingConfirmed.length > 0) {
    const mostRecent = [...pendingConfirmed].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
    return { order: mostRecent, state: 'PENDING_CONFIRMED' };
  }

  const completed = orders.filter((o) => o.orderStatus === 'COMPLETED');
  if (completed.length > 0) {
    const latestCompleted = [...completed].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0];
    // Acknowledged (reviewed, or explicitly dismissed with "לא עכשיו") -> no prompt at all. The
    // older completed orders are deliberately NOT considered as fallbacks.
    if (!acknowledgedOrderIds.includes(latestCompleted.id)) {
      return { order: latestCompleted, state: 'COMPLETED_UNACKNOWLEDGED' };
    }
  }

  return null;
}

/**
 * Click-through route for the indicator's currently-selected order (§8).
 *
 * The `COMPLETED_UNACKNOWLEDGED` branch is no longer what the floating indicator does on click —
 * that state now opens `ReviewPromptModal` in place (see `ActiveOrderIndicator`) instead of
 * navigating. The mapping is kept because `/orders/:id/review` is still a real, reachable screen
 * (order tracking and the SOS completion screen both link to it), so "where does this order's
 * review live" stays answerable in one place.
 */
export function resolveActiveOrderRoute(selection: ActiveOrderSelection): string {
  if (selection.state === 'COMPLETED_UNACKNOWLEDGED') {
    return `/orders/${selection.order.id}/review`;
  }
  return `/orders/${selection.order.id}`;
}
