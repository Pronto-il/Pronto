package com.pronto.matching;

import java.math.BigDecimal;

/**
 * Result of a single {@link DistanceEtaStrategy#calculate} call — a coarse, approximated
 * distance/ETA figure for one professional against one customer service location. See
 * {@link ApproximateDistanceEtaStrategy}'s Javadoc for exactly how each field is derived;
 * never persisted (approved design §1 classification items 7-8).
 */
public record EtaResult(
        boolean sameCity,
        BigDecimal distanceKm,
        int baseTravelTimeMinutes,
        int trafficAdjustmentMinutes,
        int etaMinutes
) {
}
