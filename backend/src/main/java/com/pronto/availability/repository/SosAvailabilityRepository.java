package com.pronto.availability.repository;

import com.pronto.availability.entity.SosAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * See {@code docs/architecture/data-model.md} §2.6. The initial {@code isAvailable = false}
 * row is inserted by {@code auth.service.AuthService#register} on professional registration;
 * {@code findById} (the entity's PK is {@code professionalId} itself, not a surrogate id) is
 * this interface's own read of that row, used both by {@code bookings.service.BookingsService}
 * (§2.13 step 9's plain read-check) and {@code availability.service.AvailabilityService}
 * (§2.15). {@link #updateAvailability} is Milestone 4's toggle-write method (§2.14).
 */
public interface SosAvailabilityRepository extends JpaRepository<SosAvailability, Long> {

    /**
     * §2.14 step 4: a plain, unconditional {@code UPDATE} — deliberately *not* the §3.2
     * guarded-transition pattern every other state change in this doc uses, because there is
     * no "wrong state to toggle from" here (see §2.14's note in
     * {@code docs/architecture/api-contract-bookings.md}). Affected-row count of {@code 0}
     * means the row is missing entirely — a data-integrity bug (§2.14 step 5), not a normal
     * error path, handled by the caller as {@code 500 INTERNAL_ERROR} logged at {@code WARN}.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosAvailability s SET s.isAvailable = :isAvailable, s.updatedAt = :now "
            + "WHERE s.professionalId = :professionalId")
    int updateAvailability(@Param("professionalId") Long professionalId,
                            @Param("isAvailable") boolean isAvailable, @Param("now") Instant now);
}
