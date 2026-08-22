import { useCallback, useEffect, useRef, useState } from 'react';
import {
  expandSosSearch,
  getSosCandidates,
  getSosRequest,
  hasSosSelection,
  isSosTerminalStatus,
} from '../api/sos';
import type { SosCandidate, SosRequestResponse } from '../api/sos';
import { ApiError } from '../api/httpClient';
import { GENERIC_ERROR_MESSAGE } from '../api/errorMessages';
import type { StompConnectionStatus } from '../realtime';
import { usePolling } from './usePolling';
import { useSosRealtime } from './useSosRealtime';

/**
 * Poll interval while a request is live. Faster than the 4s the order-tracking screen uses,
 * because this screen's whole subject is a two-minute window — and it is only the *fallback*:
 * with the socket up, every real change lands via a realtime-triggered refetch well before the
 * next tick.
 */
const LIVE_POLL_INTERVAL_MS = 3000;

export interface UseSosRequestResult {
  request: SosRequestResponse | null;
  /**
   * Professionals who have positively responded "I am available", newest server view first-ETA
   * ordered. Populated as they answer — the customer never waits for all three.
   */
  candidates: SosCandidate[];
  /** The backend's authority on whether `/select` will be accepted right now. */
  selectionOpen: boolean;
  isLoading: boolean;
  error: Error | null;
  refetch: () => void;
  realtimeStatus: StompConnectionStatus;
  /**
   * Widen the search on this same request ("סרוק שוב"). Resolves once the server has answered and
   * the screen has been refetched, so the caller never has to reconcile anything itself.
   *
   * Safe to call twice — the client drops a second call while one is in flight, and the backend's
   * compare-and-set means even two that got through would produce exactly one expansion.
   */
  expandSearch: () => Promise<void>;
  /** True while an expansion request is in flight. The control disables itself on this. */
  isExpanding: boolean;
  /** Hebrew message from the last failed expansion, or `null`. */
  expandError: string | null;
}

/**
 * The customer's single source of SOS state: canonical REST, accelerated by realtime.
 *
 * ## The contract this hook exists to enforce
 *
 * **REST is the truth.** `GET /api/sos/requests/{id}` re-applies elapsed deadlines server-side on
 * every read, so a request whose window closed is never served as live. This hook therefore holds
 * only what those endpoints returned — it never patches status, counts or deadlines from a
 * realtime payload, and never derives a state transition from a client-side timer.
 *
 * **Realtime only makes it faster.** Any SOS message for this request triggers an immediate
 * refetch; so does every (re)subscribe, which is what covers whatever was missed while the socket
 * was down. Polling continues underneath regardless, so the screen is correct with the socket
 * permanently dead — it is merely slower.
 *
 * ## Two deliberate details
 *
 * *Candidates stop being fetched once a professional is selected or the request ends.* The
 * endpoint would return an empty list at that point (selection closes every other offer out of
 * `ACCEPTED`), and the last pre-selection list is retained instead — it still contains the winning
 * offer, which is what the post-selection panel renders from. Without that, the customer would
 * watch the professional they just chose vanish from the screen.
 *
 * *Polling stops at a terminal status*, matching `useOrderStatus`'s precedent: nothing can change
 * again, so there is nothing to ask about.
 *
 * @param sosRequestId the request to track, or `null` before one has been activated
 */
export function useSosRequest(sosRequestId: number | null): UseSosRequestResult {
  const [isTerminal, setIsTerminal] = useState(false);
  const [isExpanding, setIsExpanding] = useState(false);
  const [expandError, setExpandError] = useState<string | null>(null);
  /**
   * Guards against a double-tap producing two requests. Deliberately a ref, not the state above:
   * two clicks in the same tick would both read a stale `isExpanding` and both fire.
   *
   * This is a courtesy, not the protection. `POST .../scan-again` advances a compare-and-set
   * counter server-side, so even two requests that got through produce exactly one expansion and
   * one dispatch wave — see `expandSosSearch`.
   */
  const expandInFlightRef = useRef(false);

  /**
   * Whether the candidates endpoint is still worth calling — see the doc comment. Read before the
   * fetch and rewritten from its result, so the tick on which a selection lands still asks (and
   * harmlessly gets an empty list, which is discarded below), and no tick after it does.
   */
  const wantsCandidatesRef = useRef(true);
  /** Last non-empty candidate view, retained across the selection boundary. */
  const lastCandidatesRef = useRef<SosCandidate[]>([]);

  useEffect(() => {
    // A different request (a retry on the same issue) starts from a clean slate.
    wantsCandidatesRef.current = true;
    lastCandidatesRef.current = [];
    expandInFlightRef.current = false;
    setIsTerminal(false);
    setIsExpanding(false);
    setExpandError(null);
  }, [sosRequestId]);

  const fetcher = useCallback(async () => {
    if (sosRequestId === null) {
      throw new Error('No SOS request to track.');
    }
    const askForCandidates = wantsCandidatesRef.current;
    const [request, candidatesResponse] = await Promise.all([
      getSosRequest(sosRequestId),
      askForCandidates ? getSosCandidates(sosRequestId) : Promise.resolve(null),
    ]);

    const stillSearching = !hasSosSelection(request.status) && !isSosTerminalStatus(request.status);
    wantsCandidatesRef.current = stillSearching;
    if (candidatesResponse && stillSearching) {
      lastCandidatesRef.current = candidatesResponse.candidates;
    }

    return {
      request,
      candidates: lastCandidatesRef.current,
      // `selectionOpen` comes from the candidates response when we have a fresh one, and is
      // otherwise derived from the request's own status — never from a local clock.
      selectionOpen: candidatesResponse
        ? candidatesResponse.selectionOpen
        : request.status === 'WAITING_FOR_CUSTOMER_SELECTION',
    };
  }, [sosRequestId]);

  const enabled = sosRequestId !== null && !isTerminal;
  const { data, error, isLoading, refetch } = usePolling(fetcher, {
    enabled,
    intervalMs: LIVE_POLL_INTERVAL_MS,
  });

  useEffect(() => {
    if (data && isSosTerminalStatus(data.request.status)) {
      setIsTerminal(true);
    }
  }, [data]);

  // `usePolling` returns a new `refetch` identity every render; the socket must not be rebuilt
  // for that, so realtime is handed a stable wrapper.
  const refetchRef = useRef(refetch);
  refetchRef.current = refetch;
  const stableRefetch = useCallback(() => refetchRef.current(), []);

  const { status: realtimeStatus } = useSosRealtime({
    enabled,
    onEvent: (message) => {
      // A customer session can legitimately hold several SOS requests over time (a retry, or an
      // older one still tracking); only this screen's request is our business.
      if (message.sosRequestId === sosRequestId) {
        stableRefetch();
      }
    },
    onResync: stableRefetch,
  });

  const expandSearch = useCallback(async () => {
    if (sosRequestId === null || expandInFlightRef.current) {
      return;
    }
    expandInFlightRef.current = true;
    setIsExpanding(true);
    setExpandError(null);
    try {
      await expandSosSearch(sosRequestId);
    } catch (err) {
      setExpandError(toExpandMessage(err));
    } finally {
      expandInFlightRef.current = false;
      setIsExpanding(false);
      // On both paths. Success means new offers exist and the deadline moved; every failure means
      // the server knows something this screen does not (somebody was selected, the ceiling was
      // reached, the request expired), and the refetch is what makes the screen agree.
      refetchRef.current();
    }
  }, [sosRequestId]);

  return {
    request: data?.request ?? null,
    candidates: data?.candidates ?? [],
    selectionOpen: data?.selectionOpen ?? false,
    isLoading,
    error,
    refetch: stableRefetch,
    realtimeStatus,
    expandSearch,
    isExpanding,
    expandError,
  };
}

/**
 * Hebrew for a failed expansion, never the backend's English message (FRONTEND_AGENT.md §26).
 *
 * The two interesting codes are both "the world moved on", not "something broke", and the refetch
 * that follows will already have re-rendered the screen correctly — so the copy explains rather
 * than alarms.
 */
function toExpandMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'SOS_EXPANSION_LIMIT_REACHED') {
      return 'הרחבנו את החיפוש עד הסוף. אפשר לבחור מבין מי שכבר אישר שהוא זמין.';
    }
    if (SOS_EXPAND_ERROR_MESSAGES[error.code]) {
      return SOS_EXPAND_ERROR_MESSAGES[error.code];
    }
  }
  return GENERIC_ERROR_MESSAGE;
}

const SOS_EXPAND_ERROR_MESSAGES: Record<string, string> = {
  SOS_ALREADY_SELECTED: 'כבר נבחר בעל מקצוע לקריאה הזו, ולכן החיפוש נעצר.',
  SOS_WINDOW_EXPIRED: 'הזמן לקריאה הזו נגמר. אפשר להתחיל קריאה חדשה על אותה תקלה.',
  SOS_INVALID_STATE: 'הקריאה השתנתה בינתיים. רגע, מרעננים את המצב.',
};
