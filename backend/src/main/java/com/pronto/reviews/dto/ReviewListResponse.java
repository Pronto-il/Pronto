package com.pronto.reviews.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response body for {@code GET /api/reviews?professionalId=}.
 */
public record ReviewListResponse(
        Long professionalId,
        BigDecimal averageRating,
        long reviewCount,
        List<ReviewResponse> reviews
) {
}
