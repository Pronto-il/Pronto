package com.pronto.reviews.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Wire shape for {@code POST /api/reviews}. Deliberately carries no {@code professionalId}/
 * {@code customerId} — both are derived server-side from the loaded {@code orderId}, never
 * trusted from the request body (see {@code ReviewsService#createReview}).
 */
public record CreateReviewRequest(
        @NotNull Long orderId,
        @Min(1) @Max(5) int rating,
        @Size(max = 2000) String comment
) {
}
