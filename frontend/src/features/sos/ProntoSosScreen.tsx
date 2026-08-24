import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Card, Modal, Skeleton } from '../../shared/components';
import { useBookingDraft, useSosRequest } from '../../shared/hooks';
import {
  ApiError,
  GENERIC_ERROR_MESSAGE,
  cancelSosRequest,
  selectSosProfessional,
} from '../../shared/api';
import type { SosCandidate } from '../../shared/api';
import { SosCandidateTray } from './SosCandidateTray';
import { SosHeader } from './SosHeader';
import { SosProfessionalSheet } from './SosProfessionalSheet';
import { SosScanPanel } from './SosScanPanel';
import { SosSelectedProfessionalPanel } from './SosSelectedProfessionalPanel';
import {
  SOS_ERROR_MESSAGES,
  SOS_SELECTION_PENDING_HINT,
  SOS_STATUS_COPY,
  toSosUiPhase,
} from './sosUiState';
import styles from './ProntoSosScreen.module.css';

export interface ProntoSosScreenProps {
  sosRequestId: number;
  /**
   * Start a fresh SOS attempt on the same issue. Owned by the entry page, which holds the issue
   * and address context — a retry re-activates SOS, it never re-creates the problem.
   */
  onRetry: () => void;
  isRetrying: boolean;
  retryError?: string | null;
}

/** Hebrew copy for a failed action, never the backend's English message (FRONTEND_AGENT.md §26). */
function toUserMessage(error: unknown): string {
  if (error instanceof ApiError && SOS_ERROR_MESSAGES[error.code]) {
    return SOS_ERROR_MESSAGES[error.code];
  }
  return GENERIC_ERROR_MESSAGE;
}

/**
 * **The one continuous live SOS screen.** Everything from "we're looking" through "they've
 * arrived" happens here, driven by the request's own status — not by a sequence of routes, which
 * would make a refresh, a realtime push and a back button three different problems.
 *
 * Three areas, all present in every phase but weighted differently by it:
 * 1. `SosHeader` — what is happening, in words.
 * 2. `SosScanPanel` — where, and how the search is going.
 * 3. `SosCandidateTray` / `SosSelectedProfessionalPanel` — who, and what the customer can do.
 *
 * State comes entirely from `useSosRequest` (REST canonical, realtime-accelerated). This
 * component holds only what is genuinely local: which button is mid-submit, and whether the
 * cancel dialog is open.
 *
 * **MS3**: there is no "סרוק שוב" control here any more. The search widens by itself every two
 * minutes for as long as the scan window is open, on a schedule the backend owns, so the
 * customer has nothing to press and nothing to decide about how hard the platform is looking.
 * What is left for them is the two things only they can answer: choosing somebody, and calling
 * it off.
 */
export default function ProntoSosScreen({ sosRequestId, onRetry, isRetrying, retryError }: ProntoSosScreenProps) {
  const navigate = useNavigate();
  const { draft, clearDraft } = useBookingDraft();
  const { request, candidates, selectionOpen, isLoading, error, refetch, realtimeStatus } =
    useSosRequest(sosRequestId);

  /** The offer whose `בחר` was pressed. Non-null for the whole round trip — one submit at a time. */
  const [pendingOfferId, setPendingOfferId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [isCancelConfirmOpen, setIsCancelConfirmOpen] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  /**
   * Which candidate's details surface is open, held as an **offer id rather than the candidate
   * object**. The object is re-created on every poll tick, so storing it would pin the sheet to a
   * snapshot: an ETA revised while the sheet is open would not show, and a candidate who expired
   * would keep being offered for selection. Holding the id and re-deriving from the live list on
   * every render means the sheet is always looking at canonical state, and closes itself when that
   * state says the candidate is gone.
   */
  const [openCandidateOfferId, setOpenCandidateOfferId] = useState<number | null>(null);

  async function handleSelect(candidate: SosCandidate) {
    // Double-submit guard. The backend's atomic guarded update is the real protection — two taps
    // cannot both win there — but a second in-flight request would still produce a confusing
    // "already selected" error for an action that actually succeeded.
    if (pendingOfferId !== null) {
      return;
    }
    setActionError(null);
    setPendingOfferId(candidate.offerId);
    // The sheet is a step on the way to this action, not a place to stay once it has been taken —
    // and on success the screen behind it becomes the tracking view, which the sheet would cover.
    setOpenCandidateOfferId(null);
    try {
      const selected = await selectSosProfessional(sosRequestId, candidate.offerId);
      // Selection creates a real order, which is the draft's own "flow finished" condition
      // (`bookingDraftContext` §4.5.1 — post-order-creation success is one of its two legal
      // clear sites). Left in place, the resume indicator would keep offering to continue a
      // booking the customer has just completed.
      if (draft?.issueId === selected.issueId) {
        clearDraft();
      }
    } catch (err) {
      setActionError(toUserMessage(err));
    } finally {
      setPendingOfferId(null);
      // On both paths: success moves the screen into tracking, and every failure here
      // ("expired", "already selected", "no longer available") means the server knows something
      // this screen does not.
      refetch();
    }
  }

  async function handleCancel() {
    setActionError(null);
    setIsCancelling(true);
    try {
      await cancelSosRequest(sosRequestId);
    } catch (err) {
      setActionError(toUserMessage(err));
    } finally {
      setIsCancelling(false);
      setIsCancelConfirmOpen(false);
      refetch();
    }
  }

  if (!request) {
    return isLoading ? (
      <div className={styles.screen}>
        <Skeleton variant="rect" className={styles.loadingHeader} />
        <Skeleton variant="rect" className={styles.loadingPanel} />
      </div>
    ) : (
      <Card className={styles.errorCard}>
        <p className={styles.errorTitle}>לא הצלחנו לטעון את מצב הקריאה</p>
        <p className={styles.errorBody}>{error ? 'אפשר לנסות שוב בעוד רגע.' : GENERIC_ERROR_MESSAGE}</p>
        <Button onClick={refetch} fullWidth>
          רענון
        </Button>
      </Card>
    );
  }

  const phase = toSosUiPhase(request.status);
  const copy = SOS_STATUS_COPY[request.status];
  const isSearching = phase === 'SCANNING' || phase === 'CHOOSING';
  const selectedCandidate = candidates.find((candidate) => candidate.offerId === request.selectedOfferId) ?? null;

  /**
   * Re-derived from the live list every render, never stored. Three consequences, all wanted: an
   * ETA revision re-renders the open sheet with the new figure; a candidate who expires or is
   * closed out resolves to `null`, which unmounts the sheet rather than leaving a dead surface up;
   * and nothing here can drift from what `/candidates` last said.
   */
  const openCandidate = candidates.find((candidate) => candidate.offerId === openCandidateOfferId) ?? null;

  const addressLine = request.serviceStreet
    ? `${request.serviceStreet} ${request.serviceHouseNumber ?? ''}`.trim()
    : null;

  /**
   * The customer may call it off while it is still theirs to call off. Once the professional is
   * on the way the server refuses, so the control is not offered — an action that always fails is
   * worse than no action.
   */
  const canCancel =
    request.status === 'CREATED' ||
    request.status === 'MATCHING' ||
    request.status === 'WAITING_FOR_PROFESSIONALS' ||
    request.status === 'WAITING_FOR_CUSTOMER_SELECTION' ||
    request.status === 'PROFESSIONAL_SELECTED' ||
    request.status === 'CONFIRMED';

  return (
    <div className={styles.screen}>
      <SosHeader
        status={request.status}
        categoryId={request.categoryId}
        city={request.serviceCity}
        isLive={realtimeStatus === 'connected'}
      />

      {isSearching && (
        <SosScanPanel
          state={phase === 'CHOOSING' ? 'ready' : 'scanning'}
          city={request.serviceCity}
          addressLine={addressLine}
          offerCount={request.offerCount}
          availableCount={request.acceptedCandidateCount}
          // Mobile renders these as real cards on the surface; desktop ignores them via CSS and
          // keeps the tray below. Same data, same interaction model, one composition per width.
          candidates={candidates}
          onOpenCandidate={(candidate) => setOpenCandidateOfferId(candidate.offerId)}
          searchExpansions={request.searchExpansions}
          stillSearching={request.canExpandSearch}
        />
      )}

      {/* Desktop's candidate list. Hidden below 640px, where the scan surface carries the
          candidates instead — a tall tray under the map is exactly the "one more thing to scroll
          past" that stops the mobile SOS screen being one screen. */}
      {isSearching && (
        <div className={styles.desktopTray}>
          <SosCandidateTray
            candidates={candidates}
            selectionOpen={selectionOpen}
            stillSearching={request.canExpandSearch}
            isSubmitting={pendingOfferId !== null}
            pendingOfferId={pendingOfferId}
            onSelect={handleSelect}
            onOpenDetails={(candidate) => setOpenCandidateOfferId(candidate.offerId)}
            errorMessage={actionError}
          />
        </div>
      )}

      {/* Errors have to stay reachable on mobile, where the tray that normally carries them is
          hidden. Same message, same source. */}
      {isSearching && actionError && (
        <p className={`${styles.inlineError} ${styles.mobileOnlyError}`} role="alert">
          {actionError}
        </p>
      )}

      {(phase === 'TRACKING' || phase === 'DONE') && (
        <SosSelectedProfessionalPanel request={request} candidate={selectedCandidate} />
      )}

      {phase === 'ENDED' && (
        <>
          <SosScanPanel
            state="stopped"
            city={request.serviceCity}
            addressLine={addressLine}
            offerCount={request.offerCount}
            availableCount={request.acceptedCandidateCount}
          />
          <Card className={styles.endedCard}>
            <p className={styles.endedTitle}>{copy.title}</p>
            <p className={styles.endedBody}>
              התקלה שדיווחת עליה נשמרה במלואה — התיאור, התמונות והניתוח. אפשר להפעיל SOS שוב בלי
              למלא שום דבר מחדש.
            </p>
            {retryError && (
              <p className={styles.endedError} role="alert">
                {retryError}
              </p>
            )}
            <div className={styles.endedActions}>
              <Button onClick={onRetry} loading={isRetrying} fullWidth>
                נסה שוב
              </Button>
              <Button variant="ghost" onClick={() => navigate(`/issues/${request.issueId}/booking`)} fullWidth>
                בחירת בעל מקצוע לפי תור רגיל
              </Button>
            </div>
          </Card>
        </>
      )}

      {phase === 'DONE' && request.orderId !== null && (
        <div className={styles.doneActions}>
          <Button onClick={() => navigate(`/orders/${request.orderId}/review`)} fullWidth>
            השארת ביקורת
          </Button>
          <Button variant="ghost" onClick={() => navigate(`/orders/${request.orderId}`)} fullWidth>
            לפרטי ההזמנה
          </Button>
        </div>
      )}

      {/* Errors from an action that isn't the tray's (cancel, or a selection attempt after the
          tray stopped rendering) still need somewhere to appear. */}
      {actionError && !isSearching && (
        <p className={styles.inlineError} role="alert">
          {actionError}
        </p>
      )}

      {canCancel && (
        <Button variant="ghost" onClick={() => setIsCancelConfirmOpen(true)} fullWidth>
          ביטול הקריאה
        </Button>
      )}

      {/* The details surface. Rendered *inside* this screen, which therefore stays mounted
          underneath it: the socket stays subscribed, the selection countdown keeps counting, new
          professionals keep arriving on the scan behind, and closing returns to exactly this
          state. `openCandidate` is re-derived from the live candidates list on every render, so
          the sheet tracks ETA revisions and disappears if its candidate stops being valid. */}
      <SosProfessionalSheet
        candidate={openCandidate}
        selectionOpen={selectionOpen}
        selectionHint={SOS_SELECTION_PENDING_HINT}
        isSubmitting={pendingOfferId !== null}
        isPending={pendingOfferId !== null && pendingOfferId === openCandidate?.offerId}
        onSelect={handleSelect}
        onClose={() => setOpenCandidateOfferId(null)}
      />

      <Modal
        isOpen={isCancelConfirmOpen}
        onClose={() => setIsCancelConfirmOpen(false)}
        title="לבטל את הקריאה הדחופה?"
        size="normal"
        footer={
          <div className={styles.confirmActions}>
            <Button variant="destructive" onClick={handleCancel} loading={isCancelling} fullWidth>
              ביטול הקריאה
            </Button>
            <Button variant="secondary" onClick={() => setIsCancelConfirmOpen(false)} fullWidth>
              חזרה
            </Button>
          </div>
        }
      >
        <p className={styles.confirmText}>
          נפסיק לחפש עבורך בעל מקצוע. התקלה שלך תישאר פתוחה ותוכל להפעיל SOS שוב או לבחור בעל
          מקצוע בתור רגיל.
        </p>
      </Modal>
    </div>
  );
}
