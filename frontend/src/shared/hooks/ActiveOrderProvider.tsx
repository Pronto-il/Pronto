import { useEffect, useState, type ReactNode } from 'react';
import { useAuth } from './useAuth';
import { usePolling } from './usePolling';
import { getMyOrders } from '../api/bookings';
import { ActiveOrderContext, selectActiveOrder } from './activeOrderContext';

const ACK_STORAGE_KEY = 'pronto_ack_completed_orders';

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
  const { data, refetch } = usePolling(() => getMyOrders(), { enabled: pollingEnabled });

  const acknowledgedOrderIds = ack && user && ack.ownerId === user.id ? ack.orderIds : [];
  const selection = data ? selectActiveOrder(data.orders, acknowledgedOrderIds) : null;

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

  return (
    <ActiveOrderContext.Provider value={{ selection, acknowledgeOrder, refetch }}>
      {children}
    </ActiveOrderContext.Provider>
  );
}
