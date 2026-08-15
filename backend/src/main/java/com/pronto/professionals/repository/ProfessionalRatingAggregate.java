package com.pronto.professionals.repository;

/**
 * Projection for {@link ReviewAggregateRepository#getRatingAggregate}. {@code averageRating}
 * is {@code null} (JPQL {@code AVG} over zero rows) and {@code reviewCount} is {@code 0} when
 * the professional has no reviews — always exactly one row is returned (aggregate functions
 * never produce zero result rows), so callers never need an {@code Optional}.
 */
public record ProfessionalRatingAggregate(Double averageRating, Long reviewCount) {
}
