import { useCallback, useEffect, useRef, useState } from 'react';
import { getSosCandidates, getSosRequest, hasSosSelection, isSosTerminalStatus } from '../api/sos';
import type { SosCandidate, SosRequestResponse } from '../api/sos';
import { sosRequestKey } from '../api/resourceKeys';
import type { StompConnectionStatus } from '../realtime';
import { usePolling } from './usePolling';
import { useSosRealtime } from './useSosRealtime';

/**
 * Poll interval while a request is live **and the realtime socket is not**. Faster than the
 * order-tracking screen's, because this screen's whole subject is a two-minute window and with
 * no socket the poll is the only thing that will ever tell it anything.
 */
const DISCONNECTED_POLL_INTERVAL_MS = 3000;
/**
 * Poll interval while the socket is up. Every real change already lands via a realtime-triggered
 * refetch — and on every (re)subscribe, which is what covers whatever was missed while the
 * socket was down — so what is left for the timer is reconciliation: catching a message that was
 * published while this client was between sockets and no resubscribe fired. The old code ran the
 * full 3s cadence regardless of the socket, which meant a connected screen paid for the same
 * information twice, twenty times a minute.
 */
const CONNECTED_POLL_INTERVAL_MS = 20_000;

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
 * **The polling cadence follows the socket, which is what "fallback" has to mean to be worth
 * saying.** Connected, the timer drops to a slow reconciliation pass (`CONNECTED_POLL_INTERVAL_MS`)
 * because events are doing the work; disconnected, it goes back to carrying the screen on its own
 * (`DISCONNECTED_POLL_INTERVAL_MS`). Nothing about which endpoints are read, or what is trusted,
 * changes with it.
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
 * **MS3**: the search-expansion action is gone from this hook. Widening now happens server-side
 * on the request's own schedule, so there is nothing for a client to trigger — the widened search
 * simply arrives as more offers and more candidates on the next read, like any other change.
 *
 * @param sosRequestId the request to track, or `null` before one has been activated
 */
export function useSosRequest(sosRequestId: number | null): UseSosRequestResult {
  const [isTerminal, setIsTerminal] = useState(false);

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
    setIsTerminal(false);
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

  // Declared before the poll so the cadence can follow it. `useSosRealtime` is mounted below and
  // reports back into this, which is a render-cycle later than the socket actually connects —
  // harmless, because the only cost of being one tick behind is one extra reconciliation read.
  const [realtimeStatus, setRealtimeStatus] = useState<StompConnectionStatus>('idle');
  const isSocketUp = realtimeStatus === 'connected';

  const { data, error, isLoading, refetch } = usePolling(fetcher, {
    key: sosRequestId === null ? undefined : sosRequestKey(sosRequestId),
    enabled,
    intervalMs: isSocketUp ? CONNECTED_POLL_INTERVAL_MS : DISCONNECTED_POLL_INTERVAL_MS,
    // The one place in the app that keeps polling a backgrounded tab, and only when it is the
    // sole channel left: a customer who switches tabs during a two-minute search must not come
    // back to a screen that stopped following it. With the socket up this is `false`, because
    // the socket already delivers to a hidden tab and every message triggers a refetch — so the
    // background cost is an event that actually happened, not a timer.
    pollWhenHidden: !isSocketUp,
  });

  useEffect(() => {
    if (data && isSosTerminalStatus(data.request.status)) {
      setIsTerminal(true);
    }
  }, [data]);

  // `usePolling` returns a new `refetch` identity when the key changes; the socket must not be
  // rebuilt for that, so realtime is handed a stable wrapper.
  const refetchRef = useRef(refetch);
  refetchRef.current = refetch;
  const stableRefetch = useCallback(() => refetchRef.current(), []);

  const { status } = useSosRealtime({
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

  useEffect(() => {
    setRealtimeStatus(status);
  }, [status]);

  return {
    request: data?.request ?? null,
    candidates: data?.candidates ?? [],
    selectionOpen: data?.selectionOpen ?? false,
    isLoading,
    error,
    refetch: stableRefetch,
    realtimeStatus,
  };
}
