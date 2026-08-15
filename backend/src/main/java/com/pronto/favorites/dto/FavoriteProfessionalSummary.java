package com.pronto.favorites.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One entry in {@code GET /api/favorites}'s list. A dedicated, lean DTO rather than reusing
 * {@code bookings.dto.ProfessionalCard} — this endpoint has no service-location context
 * (unlike a booking listing), so {@code ProfessionalCard}'s {@code distanceKm}/
 * {@code baseTravelTimeMinutes}/{@code trafficAdjustmentMinutes}/{@code etaMinutes}/
 * {@code sameCity} fields simply don't apply here; reusing it would mean either always-null
 * fields on this response or a confusing partially-populated card. See
 * {@code docs/architecture} design notes on the reviews/favorites/matching feature for the
 * "your call, favor the simpler option" judgment call this resolves.
 */
public record FavoriteProfessionalSummary(
        Long professionalId,
        String fullName,
        String serviceArea,
        String city,
        BigDecimal basePrice,
        String profileImageUrl,
        BigDecimal averageRating,
        long reviewCount,
        Instant favoritedAt
) {
}
