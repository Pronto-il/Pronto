package com.pronto.maps;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Coordinates in, real driving distance and duration out.
 *
 * <p><b>The batch method is the primary one.</b> {@link #routeMatrix} exists because the
 * alternative — one HTTP call per professional per listing — is the single most expensive
 * mistake this milestone could make: fifty candidates would mean fifty round trips on every
 * search, fifty billable requests, and a listing latency equal to the slowest of them. Both
 * candidate providers offer a matrix API precisely for this shape, so this interface exposes it
 * rather than hiding it behind a loop and hoping.
 *
 * <p>{@link #route} is the single-pair convenience, for the genuinely one-at-a-time call sites
 * (a professional going on the way, one order's arrival estimate). It is not a shortcut for
 * batching.
 *
 * <p><b>Never throws for a routing outcome.</b> Timeouts, rate limits, provider errors and
 * unroutable point pairs all come back as {@link RouteResult#unavailable} with a
 * {@link RouteUnavailableReason}. A caller therefore cannot accidentally handle "the provider is
 * down" by letting an exception escape into a 500 — it has to decide what an absent figure means
 * in its own flow, which is the decision MS2 is actually about.
 */
public interface RoutingProvider {

    /**
     * One origin to one destination.
     *
     * @param departureTime when the journey starts, for traffic-aware duration. Providers that
     *                      cannot use it ignore it and return
     *                      {@link RouteResult#trafficAware()} {@code == false} — the platform
     *                      then reports an honest non-traffic ETA rather than dressing one up.
     */
    RouteResult route(GeoCoordinates origin, GeoCoordinates destination, Instant departureTime);

    /**
     * Many origins to one destination, in as few provider calls as the provider allows.
     *
     * @param originsByKey caller-chosen keys (professional ids, in practice) to origins. Keys
     *                     exist so the caller never has to rely on positional correspondence
     *                     with a response array — a provider that returns elements out of order,
     *                     or omits a failed one, is a real thing and a silent mis-attribution of
     *                     one professional's ETA to another would be invisible and awful.
     * @return one entry per input key, always. A key whose element failed maps to an
     *         unavailable {@link RouteResult}, never to a missing entry — so callers cannot
     *         accidentally treat "no answer for this candidate" as "this candidate is fine".
     */
    <K> Map<K, RouteResult> routeMatrix(Map<K, GeoCoordinates> originsByKey, GeoCoordinates destination,
                                         Instant departureTime);

    /**
     * The largest number of origins this provider accepts in one {@link #routeMatrix} call.
     * Callers with more split into chunks of at most this size; the implementation does not
     * silently truncate.
     */
    int maxOriginsPerBatch();

    /** Short stable name for logs, metrics and startup-guard messages. */
    String providerName();

    /** See {@link GeocodingProvider#isFake()}. */
    boolean isFake();

    /** Whether this provider's durations account for traffic at all. */
    boolean supportsTrafficAwareDuration();

    /**
     * Convenience for the common "a list of origins, keyed by their own ids" shape. Default
     * method rather than a second abstract one — implementations have nothing to add.
     */
    default Map<Long, RouteResult> routeMatrixForIds(List<Long> ids, Map<Long, GeoCoordinates> originsById,
                                                      GeoCoordinates destination, Instant departureTime) {
        Map<Long, GeoCoordinates> filtered = new java.util.LinkedHashMap<>();
        for (Long id : ids) {
            GeoCoordinates origin = originsById.get(id);
            if (origin != null) {
                filtered.put(id, origin);
            }
        }
        return routeMatrix(filtered, destination, departureTime);
    }
}
