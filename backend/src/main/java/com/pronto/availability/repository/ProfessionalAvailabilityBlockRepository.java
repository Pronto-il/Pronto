package com.pronto.availability.repository;

import com.pronto.availability.entity.ProfessionalAvailabilityBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * See {@code docs/architecture/professional-weekly-calendar-design.md} §3.
 * {@link #findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan} is the sole custom
 * finder -- one range query, reused for three purposes: (1) {@code
 * AvailabilityDerivationService#deriveCalendar}'s block-subtraction step, (2) {@code
 * AvailabilityService#createBlock}/{@code #updateBlock}'s fast, friendly block-vs-block
 * overlap pre-check (the caller filters out its own {@code blockId} in application code when
 * editing, rather than a bespoke SQL {@code NOT} clause -- no dedicated overlap/exists query
 * is needed at the repository level, since the exclusion constraint on the table itself
 * (design §2.2) is the authoritative guard, this pre-check is only the fast/friendly first
 * line of defense), and (3) ownership loads always go through the inherited {@code findById}
 * instead. {@link #deleteByIdAndProfessionalId} is the atomic guarded delete behind {@code
 * DELETE /api/availability/blocks/{blockId}} -- no "in use" concept exists for a block (unlike
 * a slot), so this is unconditional beyond ownership.
 */
public interface ProfessionalAvailabilityBlockRepository extends JpaRepository<ProfessionalAvailabilityBlock, Long> {

    /**
     * Every block for {@code professionalId} whose range overlaps {@code [startAtLessThan's
     * lower bound, endAtGreaterThan's upper bound)} -- i.e. call with
     * {@code (professionalId, rangeEnd, rangeStart)} to find every block overlapping
     * {@code [rangeStart, rangeEnd)} ({@code start_at < rangeEnd AND end_at > rangeStart}).
     */
    List<ProfessionalAvailabilityBlock> findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan(
            Long professionalId, Instant startAtLessThan, Instant endAtGreaterThan);

    /**
     * §4.5. Ownership (existence + {@code professionalId} match) is already proven by the
     * caller's preceding {@code findById} before this is invoked -- this is a single bulk
     * {@code DELETE} statement (explicit JPQL, not a derived select-then-delete-per-entity
     * method), matching {@code AvailabilitySlotRepository.deleteSlotIfAvailable}'s existing
     * convention. No "in use" guard -- a block is never referenced by any FK, so deleting it
     * can never orphan/corrupt anything else (design §4.5).
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ProfessionalAvailabilityBlock b WHERE b.id = :id AND b.professionalId = :professionalId")
    int deleteByIdAndProfessionalId(@Param("id") Long id, @Param("professionalId") Long professionalId);
}
