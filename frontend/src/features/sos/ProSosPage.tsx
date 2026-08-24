import { useCallback, useRef, useState } from 'react';
import { Siren } from 'lucide-react';
import { Button, Modal, Skeleton } from '../../shared/components';
import { useProSos } from '../../shared/hooks';
import {
  ApiError,
  GENERIC_ERROR_MESSAGE,
  acceptSosOffer,
  completeSosRequest,
  confirmSosRequest,
  getSosOffer,
  markSosArrived,
  markSosOnTheWay,
  rejectSosOffer,
} from '../../shared/api';
import type { SosOfferResponse } from '../../shared/api';
import { SosEtaModal } from './SosEtaModal';
import { SosJobPanel } from './SosJobPanel';
import { SosOfferCard } from './SosOfferCard';
import { SOS_ERROR_MESSAGES } from './sosUiState';
import { SOS_JOB_STEPS, SOS_PRO_ERROR_MESSAGES } from './sosProUiState';
import styles from './ProSosPage.module.css';

/**
 * Hebrew for a failed professional action. The professional-specific readings win over the
 * customer-side map where the same code means something different from this side — `SOS_INVALID_STATE`
 * in particular. Falls back to the generic message; the backend's English is never shown.
 */
function toUserMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return GENERIC_ERROR_MESSAGE;
  }
  return SOS_PRO_ERROR_MESSAGES[error.code] ?? SOS_ERROR_MESSAGES[error.code] ?? GENERIC_ERROR_MESSAGE;
}

/**
 * `/pro/sos` — **"קריאות SOS"**, the professional's whole SOS surface.
 *
 * ## Why its own tab
 *
 * `/pro/requests` is an accept/reject feed of scheduled orders. An SOS offer is a different
 * resource with a two-minute clock, a different vocabulary (available ≠ awarded) and, once won, a
 * four-step operational flow. Interleaving the two in one list would mean one screen where "אישור"
 * means two different things. A fifth tab in the existing `ProDashboardLayout` nav — with a live
 * count badge, which is the actual discovery mechanism — is the smallest coherent integration.
 *
 * ## Structure, in priority order
 *
 * 1. The job they were selected for, if any. Pinned top: it is the only thing here with a deadline
 *    that costs them money.
 * 2. Offers awaiting an answer — the yes/no decision, one card each, counting down.
 * 3. Offers where they reported availability and are waiting on the customer's choice.
 * 4. Recently-closed outcomes (not selected / expired / declined / finished), briefly.
 *
 * All state comes from `useProSos` (REST canonical, realtime-accelerated, polling fallback). This
 * component owns only which action is in flight and which sheet is open. Every action refetches on
 * both success and failure — a 409/410 here means the server knows something this screen doesn't,
 * which is exactly when a stale button must stop being pressable.
 */
export default function ProSosPage() {
  const {
    incomingOffers,
    availableOffers,
    activeJob,
    resolvedOffers,
    dismissResolved,
    isLoading,
    error,
    refetch,
    realtimeStatus,
  } = useProSos();

  const [pendingOfferId, setPendingOfferId] = useState<number | null>(null);
  const [isAdvancingJob, setIsAdvancingJob] = useState(false);
  const [offerError, setOfferError] = useState<string | null>(null);
  const [jobError, setJobError] = useState<string | null>(null);
  const [etaSheet, setEtaSheet] = useState<{ offer: SosOfferResponse } | null>(null);
  const [isEtaSubmitting, setIsEtaSubmitting] = useState(false);
  const [etaError, setEtaError] = useState<string | null>(null);
  const [isCompleteConfirmOpen, setIsCompleteConfirmOpen] = useState(false);

  /**
   * Offers already reported as viewed. `GET /api/sos/offers/{id}` is a mutation server-side
   * (`OFFERED -> VIEWED`), so it fires exactly once per offer per session — never on a poll tick.
   */
  const viewedOfferIdsRef = useRef<Set<number>>(new Set());
  const handleViewed = useCallback((offerId: number) => {
    if (viewedOfferIdsRef.current.has(offerId)) {
      return;
    }
    viewedOfferIdsRef.current.add(offerId);
    // Best-effort telemetry: a failed view-mark changes nothing the professional can see, and the
    // next poll reflects the status either way.
    void getSosOffer(offerId).catch(() => undefined);
  }, []);

  const handleCountdownElapsed = useCallback(() => {
    // The clock ran out on screen. The server decides what that means.
    refetch();
  }, [refetch]);

  async function handleDecline(offer: SosOfferResponse) {
    if (pendingOfferId !== null) {
      return;
    }
    setOfferError(null);
    setPendingOfferId(offer.id);
    try {
      await rejectSosOffer(offer.id);
    } catch (err) {
      setOfferError(toUserMessage(err));
    } finally {
      setPendingOfferId(null);
      refetch();
    }
  }

  async function handleEtaSubmit(minutes: number) {
    if (!etaSheet) {
      return;
    }
    setEtaError(null);
    setIsEtaSubmitting(true);
    try {
      // Accepting is the only thing this sheet does now: the ETA committed here is final
      // (MS3 — the customer chooses on it), so there is no revise path to branch on.
      await acceptSosOffer(etaSheet.offer.id, minutes);
      setEtaSheet(null);
    } catch (err) {
      // Kept open on failure: the message belongs next to the control that produced it, and an
      // expired-window error is exactly when the professional needs to see why nothing happened.
      setEtaError(toUserMessage(err));
    } finally {
      setIsEtaSubmitting(false);
      refetch();
    }
  }

  async function runJobAction(action: 'confirm' | 'on-the-way' | 'arrived' | 'complete') {
    if (!activeJob || isAdvancingJob) {
      return;
    }
    const sosRequestId = activeJob.offer.sosRequestId;
    setJobError(null);
    setIsAdvancingJob(true);
    try {
      if (action === 'confirm') {
        await confirmSosRequest(sosRequestId);
      } else if (action === 'on-the-way') {
        await markSosOnTheWay(sosRequestId);
      } else if (action === 'arrived') {
        await markSosArrived(sosRequestId);
      } else {
        await completeSosRequest(sosRequestId);
      }
    } catch (err) {
      setJobError(toUserMessage(err));
    } finally {
      setIsAdvancingJob(false);
      setIsCompleteConfirmOpen(false);
      refetch();
    }
  }

  function handleAdvance() {
    if (!activeJob) {
      return;
    }
    const status = activeJob.request?.status ?? activeJob.offer.requestStatus;
    const step = SOS_JOB_STEPS[status];
    if (!step.action) {
      return;
    }
    // Completing is irreversible and closes the order and the issue with it — same
    // confirm-before-commitment treatment the order-tracking screen already uses.
    if (step.confirmFirst) {
      setIsCompleteConfirmOpen(true);
      return;
    }
    void runJobAction(step.action);
  }

  const isEmpty =
    !activeJob && incomingOffers.length === 0 && availableOffers.length === 0 && resolvedOffers.length === 0;

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>קריאות SOS</h1>
          <p className={styles.subtitle}>
            קריאות דחופות מלקוחות באזור שלך. הזמן להגיב קצר — נעדכן אותך כאן ברגע שמגיעה קריאה.
          </p>
        </div>
        {realtimeStatus === 'connected' && (
          <span className={styles.live}>
            <span className={styles.liveDot} aria-hidden="true" />
            עדכון חי
          </span>
        )}
      </header>

      {error && !isLoading && (
        <p className={styles.banner} role="alert">
          לא הצלחנו לטעון את קריאות ה־SOS. אנחנו ממשיכים לנסות ברקע.
        </p>
      )}

      {offerError && (
        <p className={styles.banner} role="alert">
          {offerError}
        </p>
      )}

      {isLoading && isEmpty && <Skeleton variant="rect" className={styles.loading} />}

      {activeJob && (
        <section className={styles.section} aria-label="העבודה שנבחרת אליה">
          <SosJobPanel
            job={activeJob}
            onAdvance={handleAdvance}
            isAdvancing={isAdvancingJob}
            errorMessage={jobError}
          />
        </section>
      )}

      {incomingOffers.length > 0 && (
        <section className={styles.section} aria-label="קריאות שממתינות לתשובה">
          <h2 className={styles.sectionTitle}>
            ממתינות לתשובה
            <span className={styles.count}>{incomingOffers.length}</span>
          </h2>
          <div className={styles.list}>
            {incomingOffers.map((offer) => (
              <SosOfferCard
                key={offer.id}
                offer={offer}
                onViewed={handleViewed}
                onRespondAvailable={(target) => {
                  setEtaError(null);
                  setEtaSheet({ offer: target });
                }}
                onDecline={handleDecline}
                onCountdownElapsed={handleCountdownElapsed}
                isDeclining={pendingOfferId === offer.id}
                isBusy={pendingOfferId !== null}
              />
            ))}
          </div>
        </section>
      )}

      {availableOffers.length > 0 && (
        <section className={styles.section} aria-label="קריאות שאישרת בהן זמינות">
          <h2 className={styles.sectionTitle}>אישרת זמינות — הלקוח בוחר</h2>
          <div className={styles.list}>
            {availableOffers.map((offer) => (
              <SosOfferCard key={offer.id} offer={offer} isBusy={pendingOfferId !== null} />
            ))}
          </div>
        </section>
      )}

      {resolvedOffers.length > 0 && (
        <section className={styles.section} aria-label="קריאות שהסתיימו">
          <h2 className={styles.sectionTitle}>הסתיימו</h2>
          <div className={styles.list}>
            {resolvedOffers.map((offer) => (
              <SosOfferCard key={offer.id} offer={offer} onDismiss={dismissResolved} />
            ))}
          </div>
        </section>
      )}

      {isEmpty && !isLoading && (
        <div className={styles.empty}>
          <span className={styles.emptyMark} aria-hidden="true">
            <Siren size={26} />
          </span>
          <p className={styles.emptyTitle}>אין קריאות SOS כרגע</p>
          <p className={styles.emptyBody}>
            כשלקוח באזור שלך יפתח קריאה דחופה בתחום שלך, היא תופיע כאן מיד. ודא שמצב הזמינות ל־SOS
            שלך פעיל ביומן הזמינות.
          </p>
        </div>
      )}

      <SosEtaModal
        isOpen={etaSheet !== null}
        offer={etaSheet?.offer ?? null}
        isSubmitting={isEtaSubmitting}
        errorMessage={etaError}
        onSubmit={handleEtaSubmit}
        onClose={() => {
          setEtaSheet(null);
          setEtaError(null);
        }}
      />

      <Modal
        isOpen={isCompleteConfirmOpen}
        onClose={() => setIsCompleteConfirmOpen(false)}
        title="לסיים את העבודה?"
        size="normal"
        footer={
          <div className={styles.confirmActions}>
            <Button onClick={() => void runJobAction('complete')} loading={isAdvancingJob} fullWidth>
              סיום העבודה
            </Button>
            <Button variant="secondary" onClick={() => setIsCompleteConfirmOpen(false)} fullWidth>
              חזרה
            </Button>
          </div>
        }
      >
        <p className={styles.confirmText}>
          הפעולה הזו סוגרת את הקריאה ואי אפשר לבטל אותה. הלקוח יקבל עדכון שהעבודה הושלמה ויוכל
          להשאיר ביקורת.
        </p>
      </Modal>
    </div>
  );
}
