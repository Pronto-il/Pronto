package com.pronto.reviews.dto;

import java.time.Instant;

/**
 * One entry in {@code GET /api/reviews?professionalId=}'s {@code reviews} array, and the
 * response body for {@code POST}/{@code PUT /api/reviews[/{reviewId}]}.
 */
public record ReviewResponse(
        Long id,
        Long professionalId,
        Long customerId,
        String customerName,
        Long orderId,
        int rating,
        String comment,
        Instant createdAt,
        Instant updatedAt
) {
}
