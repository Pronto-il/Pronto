package com.pronto.favorites.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One entry in {@code GET /api/favorites}'s list. A dedicated, lean DTO rather than reusing
 * {@code bookings.dto.ProfessionalCard} — this endpoint has no service-location context
 * (unlike a booking listing), so {@code ProfessionalCard}'s {@code distanceKm}/
 * {@code baseTravelTimeMinutes}/{@code trafficAdjustmentMinutes}/{@code etaMinutes}/
 * {@code sameCity} fields simply don't apply here; reusing it would mean either always-null
 * fields on this response or a confusing partially-populated card. See
 * {@code docs/architecture} design notes on the reviews/favorites/matching feature for the
 * "your call, favor the simpler option" judgment call this resolves.
 *
 * @param bookable MS1 (D-G): whether this saved professional is currently marketplace-eligible
 *                 ({@link com.pronto.professionals.ProfessionalEligibility}). Neutral by design —
 *                 it says "you cannot book this person right now", never <em>why</em>, so a
 *                 customer's favorites list cannot become a channel for learning that a
 *                 particular professional was rejected. Ineligible favorites are listed, not
 *                 deleted; see {@code FavoritesService#listFavorites}.
 */
public record FavoriteProfessionalSummary(
        Long professionalId,
        String fullName,
        String serviceRegion,
        String city,
        List<Long> categoryIds,
        BigDecimal basePrice,
        String profileImageUrl,
        BigDecimal averageRating,
        long reviewCount,
        Instant favoritedAt,
        boolean bookable
) {
}
