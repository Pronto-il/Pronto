package com.pronto.matching;

import com.pronto.maps.GeoCoordinates;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * <b>How far, and how long, from a professional to a service address.</b> The seam every
 * distance/ETA figure in this platform comes through — professional listing cards, the
 * {@code FASTEST} sort, SOS ranking and its geographic filter, and
 * {@code orders.expected_arrival_at}.
 *
 * <h2>The MS2 contract change</h2>
 *
 * This interface previously read {@code calculate(String professionalCity, ServiceLocation,
 * Instant)} and its Javadoc promised the implementation was "pure/stateless by contract — no
 * I/O, no persistence". <b>Both parts of that are now deliberately false, and the documentation
 * saying otherwise has been removed rather than left to mislead.</b>
 *
 * <ul>
 *   <li><b>The origin is a live position, not a city name.</b> A professional is usually coming
 *       from another job, not from home, so their registered base city cannot produce a true
 *       arrival estimate. Implementations resolve the origin themselves, from the professional's
 *       current device position and only when that position is fresh and precise enough —
 *       callers cannot pass an origin in, which is what makes it impossible for any call site to
 *       route from a stale fix or from a base-city centroid.</li>
 *   <li><b>The destination is coordinates, not an address string.</b> Geocoding happens once, on
 *       write, where the address is accepted — never once per professional card.</li>
 *   <li><b>There is I/O.</b> An external routing provider, a cache and a database read.
 *       Implementations are consequently Spring beans with dependencies, not pure functions, and
 *       tests exercise them with a fake provider rather than by calling a static method.</li>
 * </ul>
 *
 * <h2>Batch first</h2>
 *
 * {@link #calculateBatch} is the method listing and SOS matching use, and it exists to make the
 * N+1 failure mode structurally hard rather than merely discouraged: fifty professionals must
 * cost one or two provider calls, not fifty. {@link #calculate} is for the genuinely single-pair
 * call sites and is not a shortcut around that.
 */
public interface DistanceEtaStrategy {

    /**
     * Travel figures from one professional's current position to {@code destination}.
     *
     * @param destination may be {@code null} when the address could not be geocoded — the result
     *                    is then unavailable with
     *                    {@link com.pronto.maps.RouteUnavailableReason#DESTINATION_UNKNOWN},
     *                    rather than an exception, because an unresolvable address is an ordinary
     *                    situation every caller must already handle
     * @return never {@code null}; unavailable carries a reason
     */
    EtaResult calculate(Long professionalId, GeoCoordinates destination, Instant requestTime);

    /**
     * Travel figures for many professionals to one destination, in as few provider calls as
     * possible.
     *
     * @return one entry per input id, always — an id whose route could not be computed maps to
     *         an unavailable result with a reason, never to a missing entry. Callers therefore
     *         cannot iterate the result and silently lose the candidates that failed.
     */
    Map<Long, EtaResult> calculateBatch(Collection<Long> professionalIds, GeoCoordinates destination,
                                         Instant requestTime);
}
