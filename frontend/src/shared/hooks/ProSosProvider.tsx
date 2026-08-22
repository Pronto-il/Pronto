import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { getMySosOffers, getSosRequest, isSosOfferResolved, isSosTerminalStatus } from '../api/sos';
import type { SosOfferResponse, SosRealtimeEventType } from '../api/sos';
import { ProSosContext, type ProSosJob } from './proSosContext';
import { usePolling } from './usePolling';
import { useSosRealtime } from './useSosRealtime';
import { useToast } from './useToast';

/**
 * Fallback poll cadence while something is actually live. Offers expire in ~2 minutes, so a
 * professional staring at a countdown needs the truth sooner than the idle case does. This is the
 * *fallback*: with the socket up, every real change lands via a realtime-triggered refetch first.
 */
const ACTIVE_POLL_INTERVAL_MS = 5000;
/** Nothing in flight — the poll is only there to notice that something started. */
const IDLE_POLL_INTERVAL_MS = 20000;

/**
 * How long a finished outcome (not selected / expired / declined / completed job) stays on the
 * board. Long enough that a professional who stepped away still learns what happened, short enough
 * that a work queue doesn't turn into a history. They can also dismiss one early.
 */
const RESOLVED_VISIBLE_MS = 15 * 60 * 1000;

/** The realtime events that mean this professional's SOS state may have changed. */
const PROFESSIONAL_EVENT_TYPES: ReadonlySet<SosRealtimeEventType> = new Set<SosRealtimeEventType>([
  'SOS_OFFER_RECEIVED',
  'OFFER_RESPONSE_RECORDED',
  'SOS_OFFER_EXPIRED',
  'SOS_SELECTED',
  'SOS_NOT_SELECTED',
  'PROFESSIONAL_CONFIRMED',
  'ON_THE_WAY',
  'ARRIVED',
  'COMPLETED',
  'CANCELLED',
  'EXPIRED',
]);

/**
 * Holds the professional's Pronto SOS state, polled and realtime-accelerated.
 *
 * Mounted inside `ProDashboardLayout` (wrapping the nav and the `<Outlet />`), exactly like
 * `PendingRequestsProvider`: the nav badge and the `/pro/sos` screen share one poll and one socket
 * subscription rather than each opening their own.
 *
 * ## Canonical state, same contract as the customer side
 *
 * REST is the truth. A realtime message never patches anything here — it triggers a refetch, and
 * the render comes from what the endpoints returned. That is why `SOS_OFFER_RECEIVED` can raise a
 * toast from a payload while the offer itself still arrives through `GET /api/sos/offers`: the
 * payload is allowed to say *that* something happened, never *what the state now is*.
 *
 * ## Why one fetcher does two calls
 *
 * When a job is live, the offer list alone is not enough — the exact service address lives on
 * `GET /api/sos/requests/{id}`, which only the selected professional may read. Fetching both in
 * one tick keeps them consistent with each other, and means an action's `refetch()` updates the
 * whole panel at once instead of in two visible steps. The second call happens only while a job
 * is actually in flight.
 *
 * ## Why `includeClosed=true`
 *
 * The default inbox is live offers only. But "the customer chose someone else" moves an offer
 * straight out of that set, so a professional who was available and lost would simply watch their
 * card vanish with no explanation. Asking for everything and bucketing here is what makes
 * `NOT_SELECTED` and `EXPIRED` showable at all — see `resolvedOffers`.
 */
export function ProSosProvider({ children }: { children: ReactNode }) {
  const { showToast } = useToast();

  /** Realtime `eventId`s already acted on — a message can legitimately arrive twice (several
   *  sessions/devices, or a reconnect overlapping the old socket), and a duplicate toast for one
   *  offer is exactly the kind of noise an urgent inbox must not produce. */
  const handledEventIdsRef = useRef<Set<number>>(new Set());
  const [dismissedResolvedIds, setDismissedResolvedIds] = useState<Set<number>>(new Set());

  const fetcher = useCallback(async () => {
    const list = await getMySosOffers(true);
    const selected = list.offers.find(
      (offer) => offer.status === 'SELECTED' && !isSosTerminalStatus(offer.requestStatus),
    );
    if (!selected) {
      return { offers: list.offers, job: null as ProSosJob | null };
    }
    // Best-effort: a failed request read degrades the panel to the offer's own fields (city, fee,
    // ETA) rather than hiding a job the professional is committed to.
    const request = await getSosRequest(selected.sosRequestId).catch(() => null);
    return { offers: list.offers, job: { offer: selected, request } as ProSosJob };
  }, []);

  // Starts fast so a professional opening the dashboard mid-dispatch sees the offer promptly,
  // then settles to the idle cadence once nothing is in flight.
  const [pollIntervalMs, setPollIntervalMs] = useState(ACTIVE_POLL_INTERVAL_MS);
  const { data, error, isLoading, refetch } = usePolling(fetcher, { intervalMs: pollIntervalMs });

  useEffect(() => {
    const hasLiveWork = (data?.offers ?? []).some(
      (offer: SosOfferResponse) =>
        offer.status === 'OFFERED' ||
        offer.status === 'VIEWED' ||
        offer.status === 'ACCEPTED' ||
        (offer.status === 'SELECTED' && !isSosTerminalStatus(offer.requestStatus)),
    );
    setPollIntervalMs(hasLiveWork ? ACTIVE_POLL_INTERVAL_MS : IDLE_POLL_INTERVAL_MS);
  }, [data]);

  // `usePolling` hands back a new `refetch` identity every render; realtime must not rebuild its
  // socket for that, so it gets a stable wrapper.
  const refetchRef = useRef(refetch);
  refetchRef.current = refetch;
  const stableRefetch = useCallback(() => refetchRef.current(), []);

  const { status: realtimeStatus } = useSosRealtime({
    onEvent: (message) => {
      if (!PROFESSIONAL_EVENT_TYPES.has(message.eventType)) {
        return;
      }
      if (handledEventIdsRef.current.has(message.eventId)) {
        return;
      }
      if (handledEventIdsRef.current.size > 500) {
        // Unbounded growth over a long shift, for no benefit: ids only need to be remembered long
        // enough to spot a duplicate of the message that just arrived.
        handledEventIdsRef.current.clear();
      }
      handledEventIdsRef.current.add(message.eventId);

      // The one place a payload drives anything: raising an alert. It says a new offer exists,
      // not what it contains — the card itself renders from the refetch below.
      if (message.eventType === 'SOS_OFFER_RECEIVED') {
        showToast('קריאת SOS חדשה התקבלה', { tone: 'info', duration: 6000 });
      }
      stableRefetch();
    },
    onResync: stableRefetch,
  });

  const dismissResolved = useCallback((offerId: number) => {
    setDismissedResolvedIds((previous) => new Set(previous).add(offerId));
  }, []);

  const value = useMemo(() => {
    const offers = data?.offers ?? [];
    const job = data?.job ?? null;
    const resolvedCutoff = Date.now() - RESOLVED_VISIBLE_MS;

    const incomingOffers = offers.filter((offer) => offer.status === 'OFFERED' || offer.status === 'VIEWED');
    const availableOffers = offers.filter((offer) => offer.status === 'ACCEPTED');
    const resolvedOffers = offers.filter((offer) => {
      if (dismissedResolvedIds.has(offer.id)) {
        return false;
      }
      // `offeredAt` is the age reference rather than `respondedAt`: an EXPIRED offer was never
      // answered so has none, and an offer's whole life is ~2 minutes anyway, so its dispatch time
      // dates the outcome closely enough to decide whether it is still news.
      if (Date.parse(offer.offeredAt) < resolvedCutoff) {
        return false;
      }
      // A finished or cancelled job: the offer stays SELECTED forever, so the request's terminal
      // status is what retires it from the active panel into an outcome card.
      return isSosOfferResolved(offer.status)
        || (offer.status === 'SELECTED' && isSosTerminalStatus(offer.requestStatus));
    });

    return {
      incomingOffers,
      availableOffers,
      activeJob: job,
      resolvedOffers,
      dismissResolved,
      attentionCount: incomingOffers.length + (job ? 1 : 0),
      isLoading,
      error,
      refetch: stableRefetch,
      realtimeStatus,
    };
  }, [data, dismissedResolvedIds, dismissResolved, isLoading, error, stableRefetch, realtimeStatus]);

  return <ProSosContext.Provider value={value}>{children}</ProSosContext.Provider>;
}
