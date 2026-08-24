import { useEffect, useState } from 'react';
import { Modal, Button, Skeleton } from '../../shared/components';
import { getOrder, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { IssueDetailResponse, OrderDetailResponse, OrderSummary } from '../../shared/api';
import { OrderDetailsCard, ProntoAnalysisCard } from '../booking';
import styles from './RequestDetailsModal.module.css';

export interface RequestDetailsModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** The pending order whose card was clicked — already in the feed's `GET
   *  /api/bookings/orders/me?status=PENDING` result, so the header renders instantly. */
  order: OrderSummary;
  /** The feed's already-fetched issue for this order (`undefined` while in flight). Reused as
   *  is — this modal never re-fetches an issue the list has. */
  issue: IssueDetailResponse | undefined;
  isAccepting: boolean;
  isRejecting: boolean;
  onAccept: (orderId: number) => void;
  onReject: (orderId: number) => void;
}

/**
 * The full request, inline on "בקשות חדשות" — a `Modal` (bottom sheet on mobile, centered
 * dialog on desktop) rather than a route change, so the professional never loses the feed
 * behind it.
 *
 * **What it reuses.** The body is `features/booking`'s `OrderDetailsCard` — the exact card the
 * `/orders/:orderId` tracking screen (the one a notification opens) renders — plus
 * `ProntoAnalysisCard` for the brief, and the accept/reject handlers already owned by
 * `IncomingRequestsPage`, which still call the same `POST /api/bookings/orders/{id}/accept`
 * and `/reject`. Nothing about the backend rules changes: accept/reject remain the only two
 * actions available on a `PENDING` order, and the customer's phone shows exactly when the
 * order DTO carries it (server-scoped to a party of the order).
 *
 * **Why the extra fetch.** The feed's `OrderSummary` has no address, no customer name and no
 * phone; the full `OrderDetailResponse` does. `GET /api/bookings/orders/{orderId}` is fetched
 * once per opening — an existing either-party endpoint, no new backend surface — and the
 * already-loaded `issue` is passed straight through, so opening a request costs one request.
 */
export function RequestDetailsModal({
  isOpen,
  onClose,
  order,
  issue,
  isAccepting,
  isRejecting,
  onAccept,
  onReject,
}: RequestDetailsModalProps) {
  const [detail, setDetail] = useState<OrderDetailResponse | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const orderId = order.id;
  useEffect(() => {
    if (!isOpen) {
      return;
    }
    let cancelled = false;
    setLoadError(null);
    getOrder(orderId)
      .then((result) => {
        if (!cancelled) {
          setDetail(result);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLoadError(GENERIC_ERROR_MESSAGE);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [isOpen, orderId]);

  const isProcessing = isAccepting || isRejecting;

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="פרטי הבקשה"
      size="large"
      footer={
        <div className={styles.actions}>
          <Button variant="secondary" onClick={() => onReject(order.id)} disabled={isProcessing} loading={isRejecting}>
            דחייה
          </Button>
          <Button onClick={() => onAccept(order.id)} disabled={isProcessing} loading={isAccepting}>
            אישור
          </Button>
        </div>
      }
    >
      <div className={styles.body}>
        {loadError && (
          <div className={styles.banner} role="alert">
            <p>{loadError}</p>
          </div>
        )}

        {!detail && !loadError && <Skeleton variant="rect" className={styles.loadingCard} />}

        {detail && <OrderDetailsCard order={detail} issue={issue} isProfessionalViewer />}

        {/* Pronto's full brief — the same card the job screen shows. Server-scoped to a
            professional with an order on the issue, so its mere presence is the gate. */}
        {issue?.prontoAnalysis && (
          <ProntoAnalysisCard analysis={issue.prontoAnalysis} clarifications={issue.clarifications} />
        )}
      </div>
    </Modal>
  );
}
