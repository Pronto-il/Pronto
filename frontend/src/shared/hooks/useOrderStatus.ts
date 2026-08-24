import { useEffect, useState } from 'react';
import { getOrder } from '../api/bookings';
import type { OrderDetailResponse, OrderStatus } from '../api/bookings';
import { orderDetailKey } from '../api/resourceKeys';
import { usePolling } from './usePolling';

const TERMINAL_STATUSES: OrderStatus[] = ['COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED'];

/**
 * Cadence by lifecycle stage. What the customer is waiting for is different in each one, and the
 * old flat 4s treated them all as if they were the tensest:
 *
 * - **`PENDING`** — waiting for the professional to accept. The one transition the customer is
 *   actively watching for and can do nothing about, so it stays fast.
 * - **`CONFIRMED`** — accepted, and the appointment is in the future. This one is not a single
 *   state: a booking three days out changes nothing for three days, while a booking ten minutes
 *   out is about to become "on the way". See `confirmedIntervalFor`.
 * - **`ON_THE_WAY`** — an ETA is ticking and arrival is the next event.
 */
const PENDING_INTERVAL_MS = 8_000;
const ON_THE_WAY_INTERVAL_MS = 8_000;
const CONFIRMED_NEAR_INTERVAL_MS = 8_000;
const CONFIRMED_FAR_INTERVAL_MS = 20_000;
/** How close to the booked start "about to happen" begins. */
const CONFIRMED_NEAR_WINDOW_MS = 30 * 60 * 1000;

/**
 * A confirmed order's cadence, from how near its appointment is.
 *
 * A flat slow cadence here was measurably wrong: with 20s, the `CONFIRMED` -> `ON_THE_WAY`
 * transition took 19.9s to reach a customer sitting on the tracking screen, against 4s before
 * this work — a real freshness regression on the moment the customer most wants to see, traded
 * for requests that a booking days away does not need either way. Keying off `bookedStart` gets
 * both: an appointment that is imminent (or already due, which is exactly when the professional
 * sets off) is watched closely, and a distant one is not.
 *
 * The far branch is 20s rather than something slower because this screen is foreground-only —
 * it suspends entirely in a hidden tab — so its cost is bounded by how long the customer is
 * actually looking at it, and 20s is the worst case for noticing an early departure.
 */
function confirmedIntervalFor(bookedStart: string | null): number {
  if (!bookedStart) {
    return CONFIRMED_NEAR_INTERVAL_MS;
  }
  const msUntilStart = Date.parse(bookedStart) - Date.now();
  return msUntilStart <= CONFIRMED_NEAR_WINDOW_MS ? CONFIRMED_NEAR_INTERVAL_MS : CONFIRMED_FAR_INTERVAL_MS;
}

export interface UseOrderStatusResult {
  order: OrderDetailResponse | null;
  error: Error | null;
  isLoading: boolean;
  refetch: () => void;
}

/**
 * Order-tracking-screen polling wrapper around `usePolling` (`GET
 * /api/bookings/orders/{orderId}`). Stops polling once the last-observed `orderStatus`
 * reaches a terminal state (`COMPLETED`/`CANCELLED`/`REJECTED`/`EXPIRED`) — no point
 * short-polling a status that can never change again — and paces itself by lifecycle otherwise
 * (see the constants above).
 *
 * This is deliberately *not* folded into `ActiveOrderProvider`'s list poll even though both are
 * about the same order: the list returns `OrderSummary`, this returns `OrderDetailResponse`, and
 * the tracking screen renders fields (the full service address, the price breakdown, the
 * timestamps) that the summary does not carry. Two endpoints, two payloads, one owner each.
 *
 * The key is the order id, so a back-and-forward navigation onto the same order re-attaches to
 * the existing entry instead of re-requesting, and two components rendering the same order (the
 * screen and a modal over it) never double it.
 */
export function useOrderStatus(orderId: number): UseOrderStatusResult {
  const [isTerminal, setIsTerminal] = useState(false);
  const [intervalMs, setIntervalMs] = useState(PENDING_INTERVAL_MS);

  const { data, error, isLoading, refetch } = usePolling<OrderDetailResponse>(() => getOrder(orderId), {
    key: orderDetailKey(orderId),
    enabled: !isTerminal,
    intervalMs,
  });

  useEffect(() => {
    if (!data) {
      return;
    }
    if (TERMINAL_STATUSES.includes(data.orderStatus)) {
      setIsTerminal(true);
      return;
    }
    setIntervalMs(
      data.orderStatus === 'ON_THE_WAY'
        ? ON_THE_WAY_INTERVAL_MS
        : data.orderStatus === 'CONFIRMED'
          ? confirmedIntervalFor(data.bookedStart)
          : PENDING_INTERVAL_MS,
    );
  }, [data]);

  // A different order (navigating between two tracking screens) starts from a clean slate.
  useEffect(() => {
    setIsTerminal(false);
    setIntervalMs(PENDING_INTERVAL_MS);
  }, [orderId]);

  return { order: data, error, isLoading, refetch };
}
