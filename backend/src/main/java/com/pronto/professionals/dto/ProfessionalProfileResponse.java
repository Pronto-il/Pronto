package com.pronto.professionals.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response body for {@code GET /api/professionals/me} (self view) and
 * {@code GET /api/professionals/{professionalId}} (detail view, either role).
 * {@code favorited} is only ever populated on the {@code {professionalId}} detail endpoint
 * for a {@code CUSTOMER} caller — always {@code null} on {@code /me} (a professional can't
 * favorite themself, and this field is meaningless for a self-view).
 */
public record ProfessionalProfileResponse(
        Long id,
        Long categoryId,
        String fullName,
        String serviceArea,
        String city,
        String bio,
        BigDecimal basePrice,
        String profileImageUrl,
        BigDecimal averageRating,
        long reviewCount,
        String approvalStatus,
        Boolean favorited,
        Instant createdAt,
        Instant updatedAt
) {
}
