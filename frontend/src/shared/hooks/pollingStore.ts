/**
 * The one place in the app that decides *when* a polled GET actually goes out.
 *
 * Every short-polling hook in `shared/hooks` runs on this store rather than owning a
 * `setInterval` of its own. Three things follow from that, and they are the whole reason it
 * exists (see `docs/architecture/frontend-request-efficiency.md`):
 *
 * 1. **One poller per resource, not one per consumer.** Subscriptions are keyed by the request
 *    they describe, so the sidebar badge and the feed screen that both read
 *    `GET /api/bookings/orders/me?status=PENDING` share a single timer and a single response.
 *    The effective cadence is the *fastest* interval any live subscriber asked for, so a screen
 *    that needs 6s freshness speeds the shared poll up while it is mounted and lets it fall back
 *    to the background cadence when it unmounts.
 * 2. **Nothing polls a tab nobody is looking at.** The scheduler is suspended while
 *    `document.visibilityState !== 'visible'` and revalidates on return — except for
 *    subscriptions that explicitly opt in with `pollWhenHidden`, which exists for the one case
 *    that genuinely needs background freshness (an SOS offer's two-minute window with the
 *    realtime socket down — see `ProSosProvider`).
 * 3. **Identical concurrent reads collapse into one request.** A second consumer subscribing to a
 *    key that already has fresh data gets that data with no request at all, and a `refetch()`
 *    fired while a request is already in flight is queued rather than doubled.
 *
 * <h2>Keys are the contract</h2>
 *
 * A key must fully determine the request. Two subscribers sharing a key are declaring their
 * fetchers interchangeable — the store keeps the most recently registered one and every
 * subscriber renders from its result. `usePolling` generates a per-component-instance key when
 * the caller doesn't pass one, so an un-keyed poll behaves exactly as it did before this store
 * existed: private, unshared, one timer.
 *
 * <h2>Deliberately not a query library</h2>
 *
 * No cache keys beyond the string, no stale-while-revalidate matrix, no query invalidation
 * graph, no devtools. `FRONTEND_AGENT.md` §45's dependency rule points the same way the brief
 * for this work did: the app already coordinates shared server state through context providers,
 * and what was missing underneath them was a scheduler, not a state-management library.
 */

/** Fallback cadence for a caller that names none, unchanged from the original `usePolling`. */
export const DEFAULT_POLL_INTERVAL_MS = 4000;

/**
 * How long an entry with no subscribers is kept before it is dropped. Long enough that a route
 * change that unmounts and immediately remounts the same screen (or a StrictMode double-mount in
 * development) reuses the response instead of re-requesting it; short enough that per-order and
 * per-week keys don't accumulate for the life of the session.
 */
const EVICTION_GRACE_MS = 30_000;

export interface ResourceSnapshot<T> {
  data: T | null;
  error: Error | null;
  isLoading: boolean;
}

export interface SubscriptionOptions {
  /** How fresh *this* subscriber needs the resource. The entry polls at the smallest live value. */
  intervalMs: number;
  /** `false` keeps the subscriber attached (it still receives updates and holds the cached entry
   *  alive) but contributes no cadence — nothing here asks for a request on its behalf. */
  enabled: boolean;
  /** Opt out of visibility suspension. Only for data that must stay fresh in a background tab. */
  pollWhenHidden: boolean;
}

type Listener = () => void;

interface Subscriber {
  options: SubscriptionOptions;
  listener: Listener;
}

interface Entry {
  fetcher: () => Promise<unknown>;
  snapshot: ResourceSnapshot<unknown>;
  subscribers: Map<object, Subscriber>;
  /** `window.setTimeout` handle for the next scheduled tick, or `null` when not scheduled. */
  timer: number | null;
  /** The in-flight request, or `null`. Its presence is what collapses concurrent reads. */
  inFlight: Promise<void> | null;
  /** An explicit `refetch()` that arrived mid-request, to run as soon as that one settles. */
  queuedRefetch: boolean;
  /** `Date.now()` of the last settled request (success or failure), `0` before the first. */
  lastSettledAt: number;
  evictionTimer: number | null;
}

const entries = new Map<string, Entry>();

const EMPTY_SNAPSHOT: ResourceSnapshot<unknown> = { data: null, error: null, isLoading: true };

function isDocumentVisible(): boolean {
  return typeof document === 'undefined' || document.visibilityState === 'visible';
}

function getOrCreateEntry(key: string, fetcher: () => Promise<unknown>): Entry {
  const existing = entries.get(key);
  if (existing) {
    // Last registration wins: subscribers sharing a key have declared their fetchers equivalent,
    // and the newest one is the one whose closure is guaranteed to still be mounted.
    existing.fetcher = fetcher;
    if (existing.evictionTimer !== null) {
      window.clearTimeout(existing.evictionTimer);
      existing.evictionTimer = null;
    }
    return existing;
  }
  const created: Entry = {
    fetcher,
    snapshot: EMPTY_SNAPSHOT,
    subscribers: new Map(),
    timer: null,
    inFlight: null,
    queuedRefetch: false,
    lastSettledAt: 0,
    evictionTimer: null,
  };
  entries.set(key, created);
  return created;
}

function publish(entry: Entry, snapshot: ResourceSnapshot<unknown>): void {
  entry.snapshot = snapshot;
  entry.subscribers.forEach((subscriber) => subscriber.listener());
}

/**
 * The cadence this entry should currently run at, or `null` for "don't schedule anything".
 *
 * While the tab is hidden only `pollWhenHidden` subscribers are counted, so an entry whose
 * subscribers all poll foreground-only simply stops until the tab comes back.
 */
function effectiveIntervalMs(entry: Entry): number | null {
  const visible = isDocumentVisible();
  let smallest: number | null = null;
  entry.subscribers.forEach(({ options }) => {
    if (!options.enabled) {
      return;
    }
    if (!visible && !options.pollWhenHidden) {
      return;
    }
    if (smallest === null || options.intervalMs < smallest) {
      smallest = options.intervalMs;
    }
  });
  return smallest;
}

function clearTimer(entry: Entry): void {
  if (entry.timer !== null) {
    window.clearTimeout(entry.timer);
    entry.timer = null;
  }
}

/**
 * Re-arms the entry's next tick from `lastSettledAt`, so changing the cadence (or a subscriber
 * joining or leaving) re-times the *existing* schedule instead of firing an extra request.
 *
 * A self-rescheduling `setTimeout` rather than `setInterval`: the next tick is measured from when
 * the last one settled, which is what keeps a slow response from queuing ticks behind it, and
 * what makes "the tab was hidden for ten minutes" cost exactly one revalidation on return rather
 * than ten minutes of backlog.
 */
function schedule(entry: Entry): void {
  clearTimer(entry);
  const intervalMs = effectiveIntervalMs(entry);
  if (intervalMs === null) {
    return;
  }
  if (entry.inFlight) {
    // Re-time once the current request settles rather than stacking a timer behind it.
    void entry.inFlight.then(() => schedule(entry));
    return;
  }
  if (entry.lastSettledAt === 0) {
    void runFetch(entry).then(() => schedule(entry));
    return;
  }
  const delay = Math.max(0, entry.lastSettledAt + intervalMs - Date.now());
  entry.timer = window.setTimeout(() => {
    entry.timer = null;
    void runFetch(entry).then(() => schedule(entry));
  }, delay);
}

/**
 * Runs the entry's fetcher, collapsing concurrent callers onto one request.
 *
 * `isExplicit` is the `refetch()` path and is never dropped: it follows a user action that just
 * changed the data server-side, so if a poll is already in flight (and therefore reading state
 * from before that action) the refetch is queued and runs the moment it settles. That behaviour
 * was found live in MS5 — confirming a cancellation left the old status on screen for a full
 * interval — and is preserved here unchanged.
 */
function runFetch(entry: Entry, isExplicit = false): Promise<void> {
  if (entry.inFlight) {
    if (isExplicit) {
      entry.queuedRefetch = true;
    }
    return entry.inFlight;
  }

  const run = (async () => {
    try {
      do {
        entry.queuedRefetch = false;
        const result = await entry.fetcher();
        publish(entry, { data: result, error: null, isLoading: false });
      } while (entry.queuedRefetch);
    } catch (err) {
      // The last good `data` is deliberately kept: a failed tick should not blank a screen that
      // was showing correct state a few seconds ago.
      publish(entry, {
        data: entry.snapshot.data,
        error: err instanceof Error ? err : new Error('Polling request failed.'),
        isLoading: false,
      });
    } finally {
      entry.lastSettledAt = Date.now();
      entry.inFlight = null;
      entry.queuedRefetch = false;
    }
  })();

  entry.inFlight = run;
  return run;
}

function scheduleEviction(key: string, entry: Entry): void {
  if (entry.evictionTimer !== null) {
    window.clearTimeout(entry.evictionTimer);
  }
  entry.evictionTimer = window.setTimeout(() => {
    if (entry.subscribers.size === 0 && entries.get(key) === entry) {
      clearTimer(entry);
      entries.delete(key);
    }
  }, EVICTION_GRACE_MS);
}

export interface Subscription {
  /** Replace this subscriber's cadence/enabled/hidden flags and re-time the shared schedule. */
  update: (options: SubscriptionOptions) => void;
  unsubscribe: () => void;
}

/**
 * Attaches a subscriber to `key`, creating the shared entry on first use.
 *
 * The subscriber's `listener` fires on every published snapshot, including the one produced by
 * a request another subscriber's cadence triggered. It does **not** fire on subscribe — the
 * caller reads `getSnapshot(key)` itself, which is what lets a screen mount straight onto
 * already-fetched data without a request or a loading flash.
 */
export function subscribe(
  key: string,
  fetcher: () => Promise<unknown>,
  options: SubscriptionOptions,
  listener: Listener,
): Subscription {
  const entry = getOrCreateEntry(key, fetcher);
  const token = {};
  entry.subscribers.set(token, { options, listener });
  schedule(entry);

  return {
    update(next: SubscriptionOptions) {
      const subscriber = entry.subscribers.get(token);
      if (!subscriber) {
        return;
      }
      subscriber.options = next;
      schedule(entry);
    },
    unsubscribe() {
      entry.subscribers.delete(token);
      if (entry.subscribers.size === 0) {
        clearTimer(entry);
        scheduleEviction(key, entry);
      } else {
        schedule(entry);
      }
    },
  };
}

export function getSnapshot<T>(key: string): ResourceSnapshot<T> {
  return (entries.get(key)?.snapshot ?? EMPTY_SNAPSHOT) as ResourceSnapshot<T>;
}

/**
 * Reads `key` once if what is cached is not good enough, and does nothing at all otherwise.
 *
 * "Not good enough" is: never read, or older than `maxAgeMs`. The default (`Infinity`) is the
 * bootstrap case — read once per session and never again on a timer, which is what the
 * notification bell needs. A finite `maxAgeMs` is the screen case: a list that reads a resource
 * some other owner keeps warm should render from that cache, but must not render a view of it
 * that is arbitrarily old just because the owner's own cadence happens to be slow. `MyOrdersPage`
 * is the caller that motivated it — `ActiveOrderProvider` drops to 60s when nothing is live, and
 * opening the orders list must not therefore show a minute-old list.
 *
 * Deliberately distinct from `refetchResource`: an explicit refetch is *queued* behind an
 * in-flight request (it means "something changed, read again"), which would turn a mount-time
 * read that raced the entry's own first fetch into two requests for one piece of information.
 */
export function ensureResourceFetched(key: string, maxAgeMs: number = Infinity): void {
  const entry = entries.get(key);
  if (!entry || entry.inFlight) {
    return;
  }
  const isFreshEnough = entry.lastSettledAt !== 0 && Date.now() - entry.lastSettledAt <= maxAgeMs;
  if (isFreshEnough) {
    return;
  }
  void runFetch(entry).then(() => schedule(entry));
}

/** Forces an immediate read of `key`, queued behind an in-flight request rather than doubling it. */
export function refetchResource(key: string): void {
  const entry = entries.get(key);
  if (!entry) {
    return;
  }
  void runFetch(entry, true).then(() => schedule(entry));
}

/**
 * Publishes a value a mutation already returned as this resource's current state.
 *
 * The point is to *not* spend a GET re-reading what a `PUT` just answered with: the SOS
 * availability toggle writes its own response back through here, and the command-center banner
 * reading the same key updates from it without a request of its own.
 */
export function primeResource<T>(key: string, data: T): void {
  const entry = entries.get(key);
  if (!entry) {
    return;
  }
  entry.lastSettledAt = Date.now();
  publish(entry, { data, error: null, isLoading: false });
  schedule(entry);
}

/**
 * Drops every cached entry and stops every timer.
 *
 * **This is a correctness requirement, not a cleanup nicety.** Entries are keyed by the request,
 * not by who made it, so `bookings:orders:me` means "the current session's orders" — and the
 * current session can change without the page reloading. `AuthProvider` calls this on logout and
 * on the 401 path that ends a dead session, so the next account can never be served the previous
 * one's cached response. It is the same cross-account-leakage rule `BookingDraftProvider` and
 * `ActiveOrderProvider` already apply to their `localStorage` keys, applied to in-memory state.
 *
 * Live subscribers are left attached: they keep rendering the last snapshot they were given and
 * re-fetch on their own schedule against the new session, which is what the screens behind a
 * logout redirect need (they are about to unmount anyway).
 */
export function clearPollingStore(): void {
  entries.forEach((entry) => {
    clearTimer(entry);
    if (entry.evictionTimer !== null) {
      window.clearTimeout(entry.evictionTimer);
    }
  });
  entries.clear();
}

if (typeof document !== 'undefined') {
  // One listener for the whole app. Going hidden drops every foreground-only entry out of
  // `effectiveIntervalMs` and therefore off the schedule; coming back re-times each entry from
  // its own `lastSettledAt`, so anything already stale revalidates at once (delay clamps to 0)
  // and anything still fresh waits out the remainder of its interval.
  document.addEventListener('visibilitychange', () => {
    entries.forEach((entry) => schedule(entry));
  });
}

/** Test/debug seam: the keys currently held, with their subscriber counts and cadence. */
export function inspectPollingStore(): Record<string, { subscribers: number; intervalMs: number | null }> {
  const out: Record<string, { subscribers: number; intervalMs: number | null }> = {};
  entries.forEach((entry, key) => {
    out[key] = { subscribers: entry.subscribers.size, intervalMs: effectiveIntervalMs(entry) };
  });
  return out;
}
