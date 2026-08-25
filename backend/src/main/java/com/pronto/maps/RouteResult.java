package com.pronto.maps;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One origin-to-destination driving result: real road distance and real driving duration.
 *
 * <p>Like {@link GeocodeResult}, a result is either usable <em>with</em> figures or unusable
 * <em>without</em> them — there is no third shape where a caller might read a zero and treat it
 * as a distance. This is the type-level half of MS2's central rule: <b>no code path may surface
 * a fabricated precise distance or ETA</b>. A caller that wants a number has to acknowledge the
 * possibility that there is not one.
 *
 * @param trafficAware whether {@code durationSeconds} accounts for live/predicted traffic. Not
 *                     decoration: it is the difference between a promise the platform can defend
 *                     and one it cannot, it is what decides how long the value may be cached
 *                     ({@code pronto.maps.traffic-duration-cache-ttl-seconds} vs. the much longer
 *                     distance TTL), and it is recorded so a later milestone can tell the two
 *                     apart in historical data.
 */
public record RouteResult(
        boolean available,
        Integer distanceMeters,
        Integer durationSeconds,
        boolean trafficAware,
        RouteUnavailableReason unavailableReason
) {

    public RouteResult {
        if (available) {
            if (distanceMeters == null || durationSeconds == null) {
                throw new IllegalArgumentException("An available route must carry both a distance and a duration.");
            }
            if (distanceMeters < 0 || durationSeconds < 0) {
                throw new IllegalArgumentException("Route distance and duration must not be negative.");
            }
            if (unavailableReason != null) {
                throw new IllegalArgumentException("An available route must not carry an unavailable reason.");
            }
        } else {
            if (distanceMeters != null || durationSeconds != null) {
                throw new IllegalArgumentException("An unavailable route must not carry figures.");
            }
            if (unavailableReason == null) {
                throw new IllegalArgumentException("An unavailable route must say why.");
            }
        }
    }

    public static RouteResult available(int distanceMeters, int durationSeconds, boolean trafficAware) {
        return new RouteResult(true, distanceMeters, durationSeconds, trafficAware, null);
    }

    public static RouteResult unavailable(RouteUnavailableReason reason) {
        return new RouteResult(false, null, null, false, reason);
    }

    /** Road distance in km to one decimal, or {@code null} when unavailable. */
    public BigDecimal distanceKm() {
        if (!available) {
            return null;
        }
        return BigDecimal.valueOf(distanceMeters)
                .divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP);
    }

    /**
     * Driving duration in whole minutes, or {@code null} when unavailable.
     *
     * <p>Rounded <b>up</b>, and never below 1: a customer told "1 minute" for a 40-second drive
     * is not misled, whereas one told "0 minutes" is being told something that cannot be true.
     */
    public Integer etaMinutes() {
        if (!available) {
            return null;
        }
        return Math.max(1, (int) Math.ceil(durationSeconds / 60.0));
    }
}
