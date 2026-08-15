package com.pronto.bookings.repository;

import com.pronto.bookings.dto.ProfessionalCard;
import com.pronto.professionals.entity.Professional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * A narrow, read-only query over {@code professionals} joined to {@code users}, for
 * {@code GET /api/bookings/professionals?issueId=} (§2.2) and, since Milestone 4,
 * {@code GET /api/bookings/sos-professionals?issueId=} (§2.12, additionally joined to
 * {@code sos_availability}). Deliberately lives in {@code bookings} (not {@code professionals})
 * — the query's shape (join to {@code users}, project into a {@code bookings}-owned DTO,
 * category-scoped) is specific to this booking-context listing, not a general
 * {@code professionals} concern, mirroring why the endpoint itself lives under
 * {@code /api/bookings/*} rather than {@code /api/professionals/*} (§3.9 of the contract doc).
 * Keeps {@code professionals.repository.ProfessionalRepository} free of a reverse dependency on
 * {@code bookings}.
 *
 * <p>{@code Repository<Professional, Long>} (not {@code JpaRepository}) — this interface
 * exists purely to expose the one query below, not full CRUD over {@code Professional}
 * (already owned by {@code professionals.repository.ProfessionalRepository}).
 *
 * <p><b>Reviews/favorites profile design.</b> Both queries below now also thread
 * {@code p.city}/{@code p.profileImageKey} into the projection (raw values, resolved/consumed
 * by {@code BookingsService} afterward — see {@code ProfessionalCard}'s Javadoc) and add
 * correlated scalar subqueries over {@code reviews} (average rating, review count) and
 * {@code favorites} (scoped to the calling customer) — a narrow cross-package read into two
 * other packages' tables, same intentional pattern this interface already establishes for
 * {@code sos_availability}. Deliberately correlated <em>subqueries</em>, not a
 * {@code LEFT JOIN} + {@code GROUP BY} — avoids a wide, error-prone {@code GROUP BY} column
 * list across three joined tables' non-aggregated columns for the same result. ETA/distance
 * are deliberately NOT added to either query (computed in Java after fetch, per the approved
 * design) and the existing {@code ORDER BY p.basePrice ASC} on both is unchanged.
 */
public interface ProfessionalListingRepository extends Repository<Professional, Long> {

    /**
     * §2.2 step 6: {@code professionals} joined to {@code users} where {@code category_id =
     * issue.categoryId} and {@code users.deleted_at IS NULL}, ordered by {@code base_price
     * ASC} (cheapest first — judgment call, §7 of the contract doc).
     */
    @Query("SELECT new com.pronto.bookings.dto.ProfessionalCard(p.id, u.fullName, p.serviceArea, "
            + "p.basePrice, p.reliabilityScore, p.city, p.profileImageKey, "
            + "(SELECT AVG(r.rating) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id), "
            + "(SELECT COUNT(r) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id), "
            + "(SELECT COUNT(f) FROM com.pronto.favorites.entity.Favorite f "
            + "WHERE f.customerId = :customerId AND f.professionalId = p.id)) "
            + "FROM Professional p, com.pronto.users.entity.User u "
            + "WHERE p.userId = u.id AND p.categoryId = :categoryId AND u.deletedAt IS NULL "
            + "ORDER BY p.basePrice ASC")
    List<ProfessionalCard> listByCategory(@Param("categoryId") Long categoryId, @Param("customerId") Long customerId);

    /**
     * §2.12 step 7: {@code professionals} joined to {@code users} (same soft-delete exclusion
     * as {@link #listByCategory}) and joined to {@code sos_availability} where
     * {@code category_id = issue.categoryId} and {@code sos_availability.is_available = true}.
     * A professional with no {@code sos_availability} row at all is excluded by the join (in
     * practice shouldn't occur — every professional gets one at registration time). Ordered by
     * {@code base_price ASC}, same judgment call as {@link #listByCategory}, made
     * independently (§7 of the contract doc).
     */
    @Query("SELECT new com.pronto.bookings.dto.ProfessionalCard(p.id, u.fullName, p.serviceArea, "
            + "p.basePrice, p.reliabilityScore, p.city, p.profileImageKey, "
            + "(SELECT AVG(r.rating) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id), "
            + "(SELECT COUNT(r) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id), "
            + "(SELECT COUNT(f) FROM com.pronto.favorites.entity.Favorite f "
            + "WHERE f.customerId = :customerId AND f.professionalId = p.id)) "
            + "FROM Professional p, com.pronto.users.entity.User u, com.pronto.availability.entity.SosAvailability s "
            + "WHERE p.userId = u.id AND p.id = s.professionalId AND p.categoryId = :categoryId "
            + "AND u.deletedAt IS NULL AND s.isAvailable = true "
            + "ORDER BY p.basePrice ASC")
    List<ProfessionalCard> listSosAvailableByCategory(@Param("categoryId") Long categoryId,
                                                        @Param("customerId") Long customerId);
}
