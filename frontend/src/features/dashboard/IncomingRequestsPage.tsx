import { useEffect, useRef, useState } from 'react';
import { AnimatePresence } from 'framer-motion';
import { useLivePendingRequests } from '../../shared/hooks';
import { getIssue, acceptOrder, rejectOrder, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { IssueDetailResponse } from '../../shared/api';
import { IncomingRequestCard } from './IncomingRequestCard';
import { RequestDetailsModal } from './RequestDetailsModal';
import styles from './IncomingRequestsPage.module.css';

/**
 * "בקשות חדשות" tab — `GET /api/bookings/orders/me?status=PENDING`, short-polled per
 * `overview.md` §3.3 ("the professional's incoming-request feed" is explicitly named
 * there as a short-polling consumer alongside the tracking screen). For each pending
 * order, a follow-up `GET /api/issues/{issueId}` resolves category/description — an
 * accepted N+1 pattern at MVP scale (no batch endpoint exists), cached per issue id so a
 * later poll tick doesn't re-fetch issues already resolved.
 *
 * **The poll is not this screen's.** `PendingRequestsProvider` (mounted on `ProDashboardLayout`)
 * owns that request for the whole `/pro/*` subtree; this screen consumes it through
 * `useLivePendingRequests`, which asks the shared timer for the fast cadence while the feed is
 * on screen and releases it on unmount. Previously this component ran a second, independent 5s
 * poll of the same URL next to the provider's own — recorded at the time as an accepted
 * redundancy, and measured at exactly double the requests while `/pro/requests` was open.
 *
 * **MS6 Professional Command Center (design doc §4.3)**: tracks which order ids have already
 * been seen across poll ticks (`seenOrderIdsRef`) so a newly-appended order can play a
 * one-shot entrance animation on `IncomingRequestCard` (`isNew`) — accept/reject/polling logic
 * itself is otherwise completely unchanged.
 *
 * **Inline request details**: clicking a card opens `RequestDetailsModal` over this screen
 * instead of navigating to `/orders/{id}`. The feed stays mounted and keeps polling, the modal
 * reuses the tracking screen's own `OrderDetailsCard`, and אישור/דחייה inside it call the same
 * two handlers below — one accept/reject implementation, two entry points.
 */
export default function IncomingRequestsPage() {
  // The feed reads the shared pending-requests resource `PendingRequestsProvider` owns, and asks
  // it for the live cadence while this screen is mounted. It used to run its own 5s poll of the
  // identical URL alongside the provider's 25s one — two timers, two requests, one question.
  const { orders, error, isLoading, refetch } = useLivePendingRequests();

  const [issuesById, setIssuesById] = useState<Record<number, IssueDetailResponse>>({});
  const [processing, setProcessing] = useState<{ orderId: number; action: 'accept' | 'reject' } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  /** The order whose details are open inline, `null` when none is. A card click never
   *  navigates — the feed stays mounted and keeps polling behind the modal. */
  const [openDetailsOrderId, setOpenDetailsOrderId] = useState<number | null>(null);

  // §4.3: order-id diff across poll ticks, purely for the entrance-animation decision — never
  // read by accept/reject/polling logic.
  const seenOrderIdsRef = useRef<Set<number>>(new Set());
  const [newOrderIds, setNewOrderIds] = useState<Set<number>>(new Set());

  useEffect(() => {
    if (isLoading) {
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
    // `orders` keeps its identity between ticks that changed nothing (the shared entry only
    // publishes a new array when a response arrives), so this runs per response, not per render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orders, isLoading]);

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

  // Both actions are unchanged (same endpoints, same backend rules) and are now shared by the
  // card and the inline details view — a decision taken in the modal closes it, since the
  // order it was showing has just left the pending feed.
  async function handleAccept(orderId: number) {
    setActionError(null);
    setProcessing({ orderId, action: 'accept' });
    try {
      await acceptOrder(orderId);
      setOpenDetailsOrderId(null);
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
      setOpenDetailsOrderId(null);
      refetch();
    } catch {
      setActionError(GENERIC_ERROR_MESSAGE);
    } finally {
      setProcessing(null);
    }
  }

  const openDetails = orders.find((order) => order.id === openDetailsOrderId) ?? null;

  return (
    <div>
      {(error || actionError) && (
        <div className={styles.banner} role="alert">
          <p>{actionError ?? 'לא הצלחנו לטעון את הבקשות. אפשר לנסות שוב בעוד רגע.'}</p>
        </div>
      )}

      {isLoading && orders.length === 0 && <p>טוען…</p>}

      {!isLoading && orders.length === 0 && (
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
                onOpenDetails={setOpenDetailsOrderId}
              />
            ))}
          </AnimatePresence>
        </div>
      )}

      {/* Keyed on the order id so switching between two requests re-seeds the modal's own
          fetch state, and dropped entirely once that order leaves the feed (accepted/rejected
          /expired) rather than left showing a request that is no longer pending. */}
      {openDetails && (
        <RequestDetailsModal
          key={openDetails.id}
          isOpen
          onClose={() => setOpenDetailsOrderId(null)}
          order={openDetails}
          issue={issuesById[openDetails.issueId]}
          isAccepting={processing?.orderId === openDetails.id && processing.action === 'accept'}
          isRejecting={processing?.orderId === openDetails.id && processing.action === 'reject'}
          onAccept={handleAccept}
          onReject={handleReject}
        />
      )}
    </div>
  );
}
