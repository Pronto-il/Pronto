package com.pronto.bookings.repository;

import com.pronto.bookings.dto.ProfessionalCard;
import com.pronto.professionals.entity.Professional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * A narrow, read-only query over {@code professionals} joined to {@code users}, for
 * {@code GET /api/bookings/professionals?issueId=} (§2.2). Deliberately lives in
 * {@code bookings} (not {@code professionals}) — the query's shape (join to {@code users},
 * project into a {@code bookings}-owned DTO, category-scoped) is specific to this
 * booking-context listing, not a general {@code professionals} concern, mirroring why the
 * endpoint itself lives under {@code /api/bookings/*} rather than {@code /api/professionals/*}
 * (§3.9 of the contract doc). Keeps {@code professionals.repository.ProfessionalRepository}
 * free of a reverse dependency on {@code bookings}.
 *
 * <p>{@code Repository<Professional, Long>} (not {@code JpaRepository}) — this interface
 * exists purely to expose the one query below, not full CRUD over {@code Professional}
 * (already owned by {@code professionals.repository.ProfessionalRepository}).
 */
public interface ProfessionalListingRepository extends Repository<Professional, Long> {

    /**
     * §2.2 step 6: {@code professionals} joined to {@code users} where {@code category_id =
     * issue.categoryId} and {@code users.deleted_at IS NULL}, ordered by {@code base_price
     * ASC} (cheapest first — judgment call, §7 of the contract doc).
     */
    @Query("SELECT new com.pronto.bookings.dto.ProfessionalCard(p.id, u.fullName, p.serviceArea, "
            + "p.basePrice, p.reliabilityScore) "
            + "FROM Professional p, com.pronto.users.entity.User u "
            + "WHERE p.userId = u.id AND p.categoryId = :categoryId AND u.deletedAt IS NULL "
            + "ORDER BY p.basePrice ASC")
    List<ProfessionalCard> listByCategory(@Param("categoryId") Long categoryId);
}
