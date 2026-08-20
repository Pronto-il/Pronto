import { useCallback, useEffect, useRef, useState } from 'react';

export interface UsePollingOptions {
  /** Re-fetch interval, per `docs/architecture/overview.md` §3.3 (short-polling, 3-5s). */
  intervalMs?: number;
  /** Skip fetching entirely (e.g. once a terminal state is reached). Defaults to true. */
  enabled?: boolean;
}

export interface UsePollingResult<T> {
  data: T | null;
  error: Error | null;
  isLoading: boolean;
  refetch: () => void;
}

const DEFAULT_INTERVAL_MS = 4000;

/**
 * Generic short-polling hook: fetches immediately on mount, then re-fetches every
 * `intervalMs`. Skips a *tick* if the previous request is still in flight (never overlaps
 * requests) but never drops an explicit `refetch()` — that one is queued and runs as soon as
 * the in-flight request settles. Cleans up its interval on unmount, and is a no-op while
 * `enabled` is `false`.
 * Backing implementation for `useOrderStatus` and any other future polling need — per
 * `docs/architecture/overview.md` §3.3 (short-polling, not WebSocket).
 */
export function usePolling<T>(fetcher: () => Promise<T>, options: UsePollingOptions = {}): UsePollingResult<T> {
  const { intervalMs = DEFAULT_INTERVAL_MS, enabled = true } = options;

  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const inFlightRef = useRef(false);
  /** An explicit `refetch()` that arrived while a request was in flight, waiting to run as
   *  soon as that one settles — see `runFetch`. */
  const queuedRefetchRef = useRef(false);

  const runFetch = useCallback(async (isExplicit = false) => {
    if (inFlightRef.current) {
      // The in-flight guard exists to stop *polls* from overlapping. An explicit refetch is a
      // different thing: it follows a user action that just changed the data (cancel an order,
      // mark on the way), so dropping it would leave the screen showing the state the user
      // just changed, until the next tick up to `intervalMs` later. Queue it instead.
      // MS5: found live — confirming a cancellation left the old status on screen for ~4s.
      if (isExplicit) {
        queuedRefetchRef.current = true;
      }
      return;
    }
    inFlightRef.current = true;
    try {
      do {
        queuedRefetchRef.current = false;
        const result = await fetcherRef.current();
        setData(result);
        setError(null);
      } while (queuedRefetchRef.current);
    } catch (err) {
      setError(err instanceof Error ? err : new Error('Polling request failed.'));
    } finally {
      inFlightRef.current = false;
      queuedRefetchRef.current = false;
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    void runFetch();
    const intervalId = window.setInterval(() => {
      void runFetch();
    }, intervalMs);
    return () => window.clearInterval(intervalId);
  }, [enabled, intervalMs, runFetch]);

  return { data, error, isLoading, refetch: () => void runFetch(true) };
}
