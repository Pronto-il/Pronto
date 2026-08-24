package com.pronto.sos.dto;

import java.math.BigDecimal;

/**
 * One row of the SOS eligibility query — everything the ranker needs about a candidate,
 * fetched in a single pass.
 *
 * <p>Constructed directly by JPQL {@code SELECT new} in
 * {@code sos.repository.SosCandidateRepository}, the same projection technique
 * {@code bookings.dto.ProfessionalCard} already uses. Distance and ETA are deliberately absent:
 * they are computed in Java afterwards via {@code matching.DistanceEtaStrategy}, matching the
 * established rule that distance/ETA are never computed in SQL.
 *
 * @param averageRating {@code null} when the professional has no reviews yet — a genuinely
 *                      unknown rating, which the ranker must not conflate with a bad one.
 */
public record EligibleProfessional(
        Long professionalId,
        Long userId,
        String fullName,
        String city,
        String serviceRegion,
        BigDecimal basePrice,
        BigDecimal reliabilityScore,
        String profileImageKey,
        Double averageRating,
        Long reviewCount
) {
}
