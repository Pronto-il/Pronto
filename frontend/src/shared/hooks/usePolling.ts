import { useCallback, useEffect, useId, useRef, useState } from 'react';
import {
  DEFAULT_POLL_INTERVAL_MS,
  ensureResourceFetched,
  getSnapshot,
  refetchResource,
  subscribe,
  type ResourceSnapshot,
} from './pollingStore';

export interface UsePollingOptions {
  /** Re-fetch interval, per `docs/architecture/overview.md` §3.3 (short-polling, 3-5s). */
  intervalMs?: number;
  /** Skip fetching entirely (e.g. once a terminal state is reached). Defaults to true. */
  enabled?: boolean;
  /**
   * Share one timer and one response with every other consumer of the same key.
   *
   * Pass this when two components read the *same request* — the professional's pending-request
   * count in the sidebar and the feed screen that lists those same orders, for instance. The key
   * must fully determine the request (include the query parameters that vary), because that is
   * the whole basis on which the store decides two fetchers are interchangeable.
   *
   * Omit it for a poll only one component makes, and the hook keeps its original behaviour: a
   * private entry, keyed to this component instance, shared with nobody.
   */
  key?: string;
  /**
   * Read the resource once on mount even while `enabled` is `false`, so a screen can render
   * correct initial state without that turning into a recurring interval.
   *
   * Only meaningful together with `enabled: false` — an enabled subscription fetches on mount
   * anyway. The notification bell is what this exists for: its badge must be right on first
   * paint for every authenticated session, but it may only *poll* while an order is live.
   */
  fetchOnMountWhenDisabled?: boolean;
  /**
   * How stale a cached value may be and still satisfy `fetchOnMountWhenDisabled`. Defaults to
   * `Infinity` — "any cached value will do", the bootstrap case.
   *
   * Set it on a screen that reads a resource some other owner keeps warm: it should render from
   * that cache rather than re-requesting, but it must not render an arbitrarily old view of it
   * just because the owner's cadence is slow at that moment.
   */
  maxStaleOnMountMs?: number;
  /**
   * Keep polling while the tab is in the background. Defaults to `false` — see
   * `pollingStore.ts`. Only set this for data whose staleness has a real cost to a user who
   * isn't looking at the tab (an SOS offer's two-minute window with the realtime socket down is
   * the case it exists for); everything else revalidates on return instead.
   */
  pollWhenHidden?: boolean;
}

export interface UsePollingResult<T> {
  data: T | null;
  error: Error | null;
  isLoading: boolean;
  refetch: () => void;
}

export type { ResourceSnapshot };

/**
 * Short-polling hook: fetches on mount, then re-fetches every `intervalMs`.
 *
 * The scheduling itself lives in `pollingStore.ts`, which this is the React binding for. What
 * that buys every caller, without changing this hook's shape:
 *
 * - **Nothing polls a hidden tab** unless it asked to (`pollWhenHidden`). On return the store
 *   revalidates anything already past its interval and leaves anything still fresh alone.
 * - **Requests never overlap**, and an explicit `refetch()` that lands mid-request is queued
 *   rather than dropped — the MS5 behaviour (confirming a cancellation must not leave the old
 *   status on screen for a full interval) is unchanged.
 * - **Changing `intervalMs` re-times the schedule instead of firing an extra request.** Hooks
 *   that vary their cadence with state (`useOrderStatus`, `useSosRequest`, `ProSosProvider`) used
 *   to spend one extra request on every transition, because the effect that owned the interval
 *   re-ran and re-fetched immediately.
 * - **A `key` makes the poll shared**, so N consumers of one resource cost one request.
 */
export function usePolling<T>(fetcher: () => Promise<T>, options: UsePollingOptions = {}): UsePollingResult<T> {
  const {
    intervalMs = DEFAULT_POLL_INTERVAL_MS,
    enabled = true,
    key: explicitKey,
    pollWhenHidden = false,
    fetchOnMountWhenDisabled = false,
    maxStaleOnMountMs = Infinity,
  } = options;

  // An un-keyed poll gets a key nobody else can collide with, so it behaves exactly as an
  // unshared `setInterval` did. `useId` is stable across re-renders and across a StrictMode
  // double-mount, which is what stops the development double-effect from becoming two entries.
  const instanceId = useId();
  const key = explicitKey ?? `local:${instanceId}`;

  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const [snapshot, setSnapshot] = useState<ResourceSnapshot<T>>(() => getSnapshot<T>(key));

  useEffect(() => {
    const sync = () => setSnapshot(getSnapshot<T>(key));
    const subscription = subscribe(key, () => fetcherRef.current(), { intervalMs, enabled, pollWhenHidden }, sync);
    if (!enabled && fetchOnMountWhenDisabled) {
      // No-op when the key already holds data fresh enough for this caller — which is what makes
      // this safe to run on every mount rather than once per session.
      ensureResourceFetched(key, maxStaleOnMountMs);
    }
    // Adopt whatever the shared entry already holds — a second consumer of a warm key renders
    // real data on its first paint, with no request and no loading flash.
    sync();
    return () => subscription.unsubscribe();
  }, [key, intervalMs, enabled, pollWhenHidden, fetchOnMountWhenDisabled, maxStaleOnMountMs]);

  const refetch = useCallback(() => refetchResource(key), [key]);

  return { data: snapshot.data, error: snapshot.error, isLoading: snapshot.isLoading, refetch };
}
