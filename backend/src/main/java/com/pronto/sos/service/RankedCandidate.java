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
 */
public record RankedCandidate(
        EligibleProfessional professional,
        BigDecimal score,
        BigDecimal distanceKm,
        int etaMinutes,
        Map<String, Double> componentScores
) {
}
