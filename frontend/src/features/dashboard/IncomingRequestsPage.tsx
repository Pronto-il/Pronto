import { useEffect, useState } from 'react';
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
 */
export default function IncomingRequestsPage() {
  const { data, error, isLoading, refetch } = usePolling<MyOrdersResponse>(() => getMyOrders('PENDING'), {
    intervalMs: 5000,
  });
  const orders = data?.orders ?? [];

  const [issuesById, setIssuesById] = useState<Record<number, IssueDetailResponse>>({});
  const [processing, setProcessing] = useState<{ orderId: number; action: 'accept' | 'reject' } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

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
          {orders.map((order) => (
            <IncomingRequestCard
              key={order.id}
              order={order}
              issue={issuesById[order.issueId]}
              isAccepting={processing?.orderId === order.id && processing.action === 'accept'}
              isRejecting={processing?.orderId === order.id && processing.action === 'reject'}
              onAccept={handleAccept}
              onReject={handleReject}
            />
          ))}
        </div>
      )}
    </div>
  );
}
