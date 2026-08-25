package com.pronto.matching;

import com.pronto.maps.RouteUnavailableReason;

import java.math.BigDecimal;

/**
 * One professional's travel figures for one destination — or an explicit statement that there
 * are none.
 *
 * <h2>What changed in Production MS2, and why the shape had to</h2>
 *
 * Before MS2 this record was {@code (sameCity, distanceKm, baseTravelTimeMinutes,
 * trafficAdjustmentMinutes, etaMinutes)} with primitive {@code int}s, and that shape encoded two
 * assumptions that are now both false. First, that a figure always exists: an {@code int
 * etaMinutes} has no way to say "we do not know", so the old implementation had to invent one,
 * which is how every professional in the platform came to be 8 or 35 km away. Second, that the
 * ETA is assembled from a base time plus a traffic surcharge — a decomposition that belonged to
 * the hardcoded peak-hour heuristic and has no meaning against a real routing provider, which
 * returns one traffic-aware duration and no breakdown.
 *
 * <p>So: {@link #distanceKm} and {@link #etaMinutes} are nullable, and a caller that wants a
 * number has to acknowledge it might not get one. That is the whole mechanism behind MS2's
 * central rule. {@code sameCity} is gone with the string comparison that produced it — the
 * question a customer was really asking it ("are they nearby") is now answered by a real
 * distance in kilometres.
 *
 * <p>Still never persisted as a record: {@code orders.expected_arrival_at} persists one derived
 * instant computed from one result, exactly as before.
 *
 * @param trafficAware whether {@link #etaMinutes} accounts for traffic. Carried through from the
 *                     provider rather than assumed, because MS2 forbids dressing a plain duration
 *                     up as a traffic-aware one.
 */
public record EtaResult(
        boolean available,
        BigDecimal distanceKm,
        Integer etaMinutes,
        boolean trafficAware,
        RouteUnavailableReason unavailableReason
) {

    public EtaResult {
        if (available) {
            if (distanceKm == null || etaMinutes == null) {
                throw new IllegalArgumentException("An available ETA result must carry both a distance and an ETA.");
            }
            if (unavailableReason != null) {
                throw new IllegalArgumentException("An available ETA result must not carry an unavailable reason.");
            }
        } else {
            if (distanceKm != null || etaMinutes != null) {
                throw new IllegalArgumentException("An unavailable ETA result must not carry figures — this is "
                        + "the type-level guarantee that no fabricated distance/ETA reaches a customer.");
            }
            if (unavailableReason == null) {
                throw new IllegalArgumentException("An unavailable ETA result must say why.");
            }
        }
    }

    public static EtaResult available(BigDecimal distanceKm, int etaMinutes, boolean trafficAware) {
        return new EtaResult(true, distanceKm, etaMinutes, trafficAware, null);
    }

    public static EtaResult unavailable(RouteUnavailableReason reason) {
        return new EtaResult(false, null, null, false, reason);
    }

    /** The reason's stable name, for DTOs and logs; {@code null} when available. */
    public String unavailableReasonName() {
        return unavailableReason == null ? null : unavailableReason.name();
    }
}
