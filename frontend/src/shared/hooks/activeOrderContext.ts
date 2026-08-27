import { createContext } from 'react';
import type { OrderSummary } from '../api/bookings';

/**
 * Active-booking floating indicator (`active-booking-floating-indicator.md`). Mirrors the
 * shape/location of `bookingDraftContext.ts` — global, cross-feature client state consumed
 * both by the app shell (`AppLayout`'s `ActiveOrderIndicator`) and by
 * `features/booking/CompletionReviewPage`.
 */
/**
 * `ARRIVED` was added 2026-08-27, and it closes a real gap rather than adding a nicety:
 * `ARRIVED` is a genuine `OrderStatus` (the professional is at the customer's door — see
 * `features/booking/OrderProgressStepper`'s "הגיע אליך" step) but {@link selectActiveOrder} used
 * to match none of its three tiers, so an order in that status selected `null`. The floating
 * indicator therefore **vanished at the exact moment the professional turned up**, and reappeared
 * only once the job was marked `COMPLETED`. Every consumer of this context inherited that hole,
 * including `useNotifications`, which stopped polling mid-visit.
 */
export type ActiveOrderIndicatorState =
  | 'PENDING_CONFIRMED'
  | 'ON_THE_WAY'
  | 'ARRIVED'
  | 'COMPLETED_UNACKNOWLEDGED';

export interface ActiveOrderSelection {
  order: OrderSummary;
  state: ActiveOrderIndicatorState;
}

/** Mirrors `BookingDraftContextValue`'s minimal shape. */
export interface ActiveOrderContextValue {
  selection: ActiveOrderSelection | null;
  /**
   * Whether this session has an order actually in flight — see `isLiveActiveOrder`.
   *
   * Exposed separately from `selection` because it is a different question with a different
   * audience: `selection` is "what should the floating indicator show", which includes a finished
   * job still waiting to be reviewed. This is "is anything happening", which is what
   * `useNotifications` gates its polling on. Reading it costs no request — that is the whole
   * point of it living here rather than being re-derived from a second fetch.
   */
  hasLiveOrder: boolean;
  /** Idempotent: a no-op if `orderId` is already acknowledged. See §6.2. */
  acknowledgeOrder: (orderId: number) => void;
  /** Forces an immediate re-poll of `GET /api/bookings/orders/me` (mirrors `usePolling`'s
   *  own `refetch`). */
  refetch: () => void;
}

/**
 * "Is an order actually in flight right now?" — `PENDING`/`CONFIRMED`/`ON_THE_WAY`, and
 * deliberately **not** `COMPLETED_UNACKNOWLEDGED`.
 *
 * A completed visit awaiting its review prompt is a local UI state over a finished job: the work
 * is done, no further server-side transition will occur, and nothing more will be sent about it.
 * Treating it as live would mean a customer who never dismisses a review prompt keeps a poller
 * running indefinitely over an order that can no longer change — which is exactly the class of
 * background traffic the gate exists to remove.
 */
export function isLiveActiveOrder(selection: ActiveOrderSelection | null): boolean {
  return selection !== null && selection.state !== 'COMPLETED_UNACKNOWLEDGED';
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
  // Ranked above ON_THE_WAY: a professional already standing at the door is more immediate than
  // one still travelling. Tie-broken by most-recently-updated, i.e. whoever arrived last.
  const arrived = orders.filter((o) => o.orderStatus === 'ARRIVED');
  if (arrived.length > 0) {
    const latest = [...arrived].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0];
    return { order: latest, state: 'ARRIVED' };
  }

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
