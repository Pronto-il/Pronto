package com.pronto.availability.repository;

import com.pronto.availability.entity.AvailabilitySlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * See {@code docs/architecture/api-contract-bookings.md} §2.3/§2.10/§2.11/§3.4.
 *
 * <p>{@link #claimSlot}/{@link #releaseSlot} implement the atomic {@code UPDATE ... WHERE
 * <current-state-guard>} pattern (§3.2 of that doc) — the sole mechanism this milestone uses
 * for every state transition, including the ones {@code bookings} needs on this entity.
 * Deliberately placed here (not in {@code bookings}) since they operate purely on this
 * table's own state guard, mirroring how {@code issues.repository.IssueRepository} owns its
 * own {@code OPEN}/{@code BOOKED} guarded transitions.
 */
public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlot, Long> {

    List<AvailabilitySlot> findByProfessionalIdAndIsAvailableTrueAndStartTimeAfterOrderByStartTimeAsc(
            Long professionalId, Instant now);

    List<AvailabilitySlot> findByProfessionalIdOrderByStartTimeAsc(Long professionalId);

    /**
     * §2.4 step 8 / §3.4. Affected-row count of {@code 0} means the slot doesn't exist,
     * belongs to a different professional, is already claimed, is already in the past, or
     * lost a concurrency race — all of which the caller maps to {@code 409 SLOT_UNAVAILABLE}.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AvailabilitySlot s SET s.isAvailable = false, s.updatedAt = :now "
            + "WHERE s.id = :slotId AND s.professionalId = :professionalId "
            + "AND s.isAvailable = true AND s.startTime > :now")
    int claimSlot(@Param("slotId") Long slotId, @Param("professionalId") Long professionalId,
                   @Param("now") Instant now);

    /**
     * §2.6 step 5 / §2.7 step 6 / §3.4. Unconditional on {@code slotId} — for a future SOS
     * order {@code slotId} is {@code NULL}, and {@code WHERE id = NULL} matches zero rows by
     * ordinary SQL null-comparison semantics, so this is already a safe no-op for SOS orders
     * with no extra {@code IS NOT NULL} branch needed in application code (§3.4).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AvailabilitySlot s SET s.isAvailable = true, s.updatedAt = :now WHERE s.id = :slotId")
    int releaseSlot(@Param("slotId") Long slotId, @Param("now") Instant now);

    /**
     * §2.18 step 6 / §3.4 (extended). Affected-row count of {@code 0} means the slot's
     * {@code isAvailable} flipped to {@code false} between the caller's existence/ownership
     * check and this write (existence/ownership are already proven by the time this is
     * called) — the caller maps that to {@code 409 SLOT_IN_USE}.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AvailabilitySlot s SET s.startTime = :startTime, s.endTime = :endTime, s.updatedAt = :now "
            + "WHERE s.id = :slotId AND s.professionalId = :professionalId AND s.isAvailable = true")
    int updateSlotTimes(@Param("slotId") Long slotId, @Param("professionalId") Long professionalId,
                         @Param("startTime") Instant startTime, @Param("endTime") Instant endTime,
                         @Param("now") Instant now);

    /**
     * §2.19 step 5 / §3.4 (extended). Affected-row count of {@code 0} means the slot's
     * {@code isAvailable} flipped to {@code false} between the caller's existence/ownership
     * check and this delete (existence/ownership are already proven by the time this is
     * called) — the caller maps that to {@code 409 SLOT_IN_USE}.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM AvailabilitySlot s WHERE s.id = :slotId AND s.professionalId = :professionalId "
            + "AND s.isAvailable = true")
    int deleteSlotIfAvailable(@Param("slotId") Long slotId, @Param("professionalId") Long professionalId);
}
