package com.pronto.reviews.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Wire shape for {@code PUT /api/reviews/{reviewId}}. Allowlist: {@code orderId}/
 * {@code professionalId}/{@code customerId} are immutable and never appear in this DTO.
 */
public record UpdateReviewRequest(
        @Min(1) @Max(5) int rating,
        @Size(max = ReviewText.COMMENT_MAX_LENGTH) String comment
) {
}
