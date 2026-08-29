package com.pronto.reviews.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response body for {@code GET /api/reviews?professionalId=}.
 *
 * <p>{@code reviews} carries {@link PublicReviewResponse}, not {@link ReviewResponse} — this
 * endpoint became guest-readable (2026-08-29) and the author-facing record exposes the reviewer's
 * internal user id and the originating order id. See {@link PublicReviewResponse}'s Javadoc.
 * {@code averageRating}/{@code reviewCount} are unchanged aggregates already shown on every
 * listing card.
 */
public record ReviewListResponse(
        Long professionalId,
        BigDecimal averageRating,
        long reviewCount,
        List<PublicReviewResponse> reviews
) {
}
