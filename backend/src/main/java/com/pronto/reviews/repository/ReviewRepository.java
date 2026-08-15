package com.pronto.reviews.repository;

import com.pronto.reviews.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * {@code reviews.service.ReviewsService}'s own repository. The average-rating/review-count
 * aggregate query is deliberately NOT duplicated here — that's owned by
 * {@code professionals.repository.ReviewAggregateRepository} (a narrow read into this
 * package's table from {@code professionals}, same pattern as
 * {@code bookings.repository.ProfessionalListingRepository}).
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByOrderId(Long orderId);

    List<Review> findByProfessionalIdOrderByCreatedAtDesc(Long professionalId);

    /**
     * Ownership-guarded update, mirroring
     * {@code availability.repository.AvailabilitySlotRepository#updateSlotTimes}'s atomic
     * {@code UPDATE ... WHERE <guard>} pattern. Existence/ownership are already proven by a
     * prior read in {@code ReviewsService#updateReview} before this is called; {@code 0}
     * affected rows here means the row was concurrently deleted between that read and this
     * write.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Review r SET r.rating = :rating, r.comment = :comment, r.updatedAt = :now "
            + "WHERE r.id = :reviewId AND r.customerId = :customerId")
    int updateIfOwnedByCustomer(@Param("reviewId") Long reviewId, @Param("customerId") Long customerId,
                                 @Param("rating") short rating, @Param("comment") String comment,
                                 @Param("now") Instant now);

    /** Same ownership-guarded pattern as {@link #updateIfOwnedByCustomer}, for delete. */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Review r WHERE r.id = :reviewId AND r.customerId = :customerId")
    int deleteIfOwnedByCustomer(@Param("reviewId") Long reviewId, @Param("customerId") Long customerId);
}
