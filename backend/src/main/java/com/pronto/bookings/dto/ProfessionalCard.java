package com.pronto.bookings.dto;

import java.math.BigDecimal;

/**
 * One entry in {@code GET /api/bookings/professionals?issueId=}'s {@code professionals}
 * array. See {@code docs/architecture/api-contract-bookings.md} §2.2.
 * {@code reliabilityScore} may be {@code null} (no computation mechanism exists yet).
 */
public record ProfessionalCard(
        Long professionalId,
        String fullName,
        String serviceArea,
        BigDecimal basePrice,
        BigDecimal reliabilityScore
) {
}
