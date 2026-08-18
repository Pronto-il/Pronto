package com.pronto.matching;

import java.math.BigDecimal;

/**
 * Result of a single {@link DistanceEtaStrategy#calculate} call — a coarse, approximated
 * distance/ETA figure for one professional against one customer service location. See
 * {@link ApproximateDistanceEtaStrategy}'s Javadoc for exactly how each field is derived.
 * This record itself is never persisted (approved design §1 classification items 7-8) — a
 * later reversal (`docs/architecture/active-booking-floating-indicator.md`) does persist a
 * single derived value, {@code orders.expected_arrival_at}, computed from one such call at
 * {@code ON_THE_WAY} time, but the {@link EtaResult} object here is still always transient.
 */
public record EtaResult(
        boolean sameCity,
        BigDecimal distanceKm,
        int baseTravelTimeMinutes,
        int trafficAdjustmentMinutes,
        int etaMinutes
) {
}
