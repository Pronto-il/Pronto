import { useEffect, useState } from 'react';
import { getOrder } from '../api/bookings';
import type { OrderDetailResponse, OrderStatus } from '../api/bookings';
import { usePolling } from './usePolling';

const TERMINAL_STATUSES: OrderStatus[] = ['COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED'];

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
 * short-polling a status that can never change again.
 */
export function useOrderStatus(orderId: number): UseOrderStatusResult {
  const [isTerminal, setIsTerminal] = useState(false);

  const { data, error, isLoading, refetch } = usePolling<OrderDetailResponse>(() => getOrder(orderId), {
    enabled: !isTerminal,
  });

  useEffect(() => {
    if (data && TERMINAL_STATUSES.includes(data.orderStatus)) {
      setIsTerminal(true);
    }
  }, [data]);

  return { order: data, error, isLoading, refetch };
}
