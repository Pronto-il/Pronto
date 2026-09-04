package com.pronto.sos.service;

import com.pronto.sos.dto.EligibleProfessional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One scored candidate, ready to be dispatched an offer.
 *
 * <p>{@code componentScores} carries each named ranking component's contribution alongside the
 * total. It is not decoration: without it, "why was this professional ranked third" is
 * unanswerable after the fact, and tuning the weights becomes guesswork. It is logged at
 * dispatch and never persisted (only the final {@code score} lands on
 * {@code sos_offers.match_score}).
 *
 * <p>{@code distanceKm}/{@code etaMinutes} are <b>nullable</b>, and both are null together. Ordinary
 * candidates always have them — {@code SosMatchingService} excludes anybody it cannot route before
 * scoring, precisely so an unroutable professional is never dispatched an SOS job. The one case
 * that reaches here without them is the demo SOS presenter, who is exempt from that exclusion so a
 * demonstration does not depend on a browser having granted geolocation; the resulting offer simply
 * carries no distance and no platform estimate, which {@code sos_offers} already allows
 * ({@code distance_km} and {@code estimated_arrival_minutes} are both nullable columns). The
 * presenter still has to state a real ETA when they accept, exactly like everyone else.
 */
public record RankedCandidate(
        EligibleProfessional professional,
        BigDecimal score,
        BigDecimal distanceKm,
        Integer etaMinutes,
        Map<String, Double> componentScores
) {
}
