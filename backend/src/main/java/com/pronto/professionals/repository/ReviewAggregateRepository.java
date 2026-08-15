package com.pronto.professionals.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.pronto.reviews.entity.Review;

/**
 * A narrow, read-only query over {@code reviews} (owned by the {@code reviews} package), for
 * {@code professionals.service.ProfessionalsService} to enrich a profile response with
 * average rating / review count. Deliberately lives in {@code professionals} (not
 * {@code reviews}) — mirrors the intentional narrow-cross-package-repository pattern already
 * established by {@code bookings.repository.ProfessionalListingRepository} (a package reading
 * another package's entity for its own projection need, rather than that dependency running
 * the other direction).
 *
 * <p>{@code Repository<Review, Long>} (not {@code JpaRepository}) — this interface exists
 * purely to expose the one aggregate query below, not full CRUD over {@code Review} (already
 * owned by {@code reviews.repository.ReviewRepository}).
 */
public interface ReviewAggregateRepository extends Repository<Review, Long> {

    @Query("SELECT new com.pronto.professionals.repository.ProfessionalRatingAggregate(AVG(r.rating), COUNT(r)) "
            + "FROM Review r WHERE r.professionalId = :professionalId")
    ProfessionalRatingAggregate getRatingAggregate(@Param("professionalId") Long professionalId);
}
