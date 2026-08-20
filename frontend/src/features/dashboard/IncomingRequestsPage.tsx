import { useEffect, useRef, useState } from 'react';
import { AnimatePresence } from 'framer-motion';
import { usePolling } from '../../shared/hooks';
import { getMyOrders, getIssue, acceptOrder, rejectOrder, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { IssueDetailResponse, MyOrdersResponse } from '../../shared/api';
import { IncomingRequestCard } from './IncomingRequestCard';
import styles from './IncomingRequestsPage.module.css';

/**
 * "בקשות חדשות" tab — `GET /api/bookings/orders/me?status=PENDING`, short-polled per
 * `overview.md` §3.3 ("the professional's incoming-request feed" is explicitly named
 * there as a short-polling consumer alongside the tracking screen). For each pending
 * order, a follow-up `GET /api/issues/{issueId}` resolves category/description — an
 * accepted N+1 pattern at MVP scale (no batch endpoint exists), cached per issue id so a
 * later poll tick doesn't re-fetch issues already resolved.
 *
 * **MS6 Professional Command Center (design doc §4.3)**: tracks which order ids have already
 * been seen across poll ticks (`seenOrderIdsRef`) so a newly-appended order can play a
 * one-shot entrance animation on `IncomingRequestCard` (`isNew`) — accept/reject/polling logic
 * itself is otherwise completely unchanged.
 */
export default function IncomingRequestsPage() {
  const { data, error, isLoading, refetch } = usePolling<MyOrdersResponse>(() => getMyOrders('PENDING'), {
    intervalMs: 5000,
  });
  const orders = data?.orders ?? [];

  const [issuesById, setIssuesById] = useState<Record<number, IssueDetailResponse>>({});
  const [processing, setProcessing] = useState<{ orderId: number; action: 'accept' | 'reject' } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  // §4.3: order-id diff across poll ticks, purely for the entrance-animation decision — never
  // read by accept/reject/polling logic.
  const seenOrderIdsRef = useRef<Set<number>>(new Set());
  const [newOrderIds, setNewOrderIds] = useState<Set<number>>(new Set());

  useEffect(() => {
    if (!data) {
      return;
    }
    const currentIds = new Set(orders.map((order) => order.id));
    const newlyAppeared = new Set<number>();
    for (const id of currentIds) {
      if (!seenOrderIdsRef.current.has(id)) {
        newlyAppeared.add(id);
      }
    }
    setNewOrderIds(newlyAppeared);
    seenOrderIdsRef.current = currentIds;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data]);

  useEffect(() => {
    const missingIds = Array.from(new Set(orders.map((order) => order.issueId))).filter(
      (id) => !(id in issuesById),
    );
    if (missingIds.length === 0) {
      return;
    }
    let cancelled = false;
    Promise.all(
      missingIds.map((id) =>
        getIssue(id)
          .then((issue) => [id, issue] as const)
          .catch(() => null),
      ),
    ).then((results) => {
      if (cancelled) return;
      setIssuesById((prev) => {
        const next = { ...prev };
        for (const result of results) {
          if (result) {
            next[result[0]] = result[1];
          }
        }
        return next;
      });
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orders]);

  async function handleAccept(orderId: number) {
    setActionError(null);
    setProcessing({ orderId, action: 'accept' });
    try {
      await acceptOrder(orderId);
      refetch();
    } catch {
      setActionError(GENERIC_ERROR_MESSAGE);
    } finally {
      setProcessing(null);
    }
  }

  async function handleReject(orderId: number) {
    setActionError(null);
    setProcessing({ orderId, action: 'reject' });
    try {
      await rejectOrder(orderId);
      refetch();
    } catch {
      setActionError(GENERIC_ERROR_MESSAGE);
    } finally {
      setProcessing(null);
    }
  }

  return (
    <div>
      {(error || actionError) && (
        <div className={styles.banner} role="alert">
          <p>{actionError ?? 'לא הצלחנו לטעון את הבקשות. אפשר לנסות שוב בעוד רגע.'}</p>
        </div>
      )}

      {isLoading && !data && <p>טוען…</p>}

      {data && orders.length === 0 && (
        <div className={styles.empty}>
          <p className={styles.emptyTitle}>אין בקשות חדשות כרגע</p>
          <p>בקשות חדשות יופיעו כאן ברגע שלקוח יבחר בכם.</p>
        </div>
      )}

      {orders.length > 0 && (
        <div className={styles.list}>
          <AnimatePresence initial={false}>
            {orders.map((order) => (
              <IncomingRequestCard
                key={order.id}
                order={order}
                issue={issuesById[order.issueId]}
                isAccepting={processing?.orderId === order.id && processing.action === 'accept'}
                isRejecting={processing?.orderId === order.id && processing.action === 'reject'}
                onAccept={handleAccept}
                onReject={handleReject}
                isNew={newOrderIds.has(order.id)}
              />
            ))}
          </AnimatePresence>
        </div>
      )}
    </div>
  );
}
