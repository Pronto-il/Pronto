package com.pronto.sos.repository;

import com.pronto.professionals.ProfessionalEligibility;
import com.pronto.professionals.entity.Professional;
import com.pronto.sos.dto.EligibleProfessional;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * The SOS eligibility query: which professionals may be asked at all.
 *
 * <p>{@code Repository<Professional, Long>} rather than {@code JpaRepository} — this interface
 * exists to expose one read, not CRUD over {@code Professional} (already owned by
 * {@code professionals.repository.ProfessionalRepository}). Same narrow-read pattern, and same
 * reasoning for living in the consuming package, as
 * {@code bookings.repository.ProfessionalListingRepository}.
 *
 * <p>This is the <b>hard filter</b>. Everything it returns is genuinely askable; the ranking in
 * {@code sos.service.SosMatchingService} then orders that set and truncates it to the configured
 * pool size. Keeping the two separate matters: an eligibility rule silently expressed as a
 * scoring penalty would still occasionally dispatch to someone who should never have been asked.
 */
public interface SosCandidateRepository extends Repository<Professional, Long> {

    /**
     * Every professional eligible for an SOS request in {@code categoryId}. The clauses, and
     * why each is a hard filter rather than a ranking signal:
     *
     * <ul>
     *   <li><b>{@code p.categoryId = :categoryId}</b> — a plumber cannot take an electrical
     *       job. Not negotiable.</li>
     *   <li><b>{@code s.isAvailable = true}</b> — the professional's own live SOS toggle
     *       ({@code sos_availability}, the table the codebase already had for exactly this).
     *       An inner join, so a professional with no row at all is excluded rather than
     *       treated as available.</li>
     *   <li><b>{@code u.deletedAt IS NULL}</b> — soft-deleted accounts, the same exclusion
     *       every other professional-facing query in this codebase applies.</li>
     *   <li><b>{@link ProfessionalEligibility#ELIGIBLE_JPQL}</b> — approval <em>and</em>
     *       completed onboarding. This clause used to be a bare {@code p.approvalStatus =
     *       'APPROVED'}, written against a workflow that did not exist yet and therefore a no-op
     *       against a table where every row was {@code APPROVED}. MS1 makes the workflow real and
     *       widens the clause to the full rule (D4): a professional who has been approved but has
     *       no enabled working-hours day, no sub-service under their own category, or no
     *       verification document is not askable either — they would take an urgent job they
     *       cannot actually be scheduled or trusted for. Concatenated from the same constant the
     *       Standard listing and the single-row service guards use, so the SOS hard filter and
     *       the rest of the platform cannot disagree about who is real.</li>
     *   <li><b>{@code p.id NOT IN :excludedProfessionalIds}</b> — the caller's exclusion set
     *       (professionals already juggling live offers, and any already offered this same
     *       request on an earlier dispatch wave). Passed in rather than expressed as a
     *       subquery so the caller decides the policy and this query stays a pure filter.</li>
     * </ul>
     *
     * <p>Rating and review count come from correlated subqueries over {@code reviews}, the same
     * technique and the same rationale (no wide {@code GROUP BY} across joined tables) that
     * {@code ProfessionalListingRepository} documents.
     *
     * <p>No {@code LIMIT}: the pool cap is applied after ranking, in Java, because the ranking
     * is not expressible in this query. The result set is bounded in practice by category plus
     * live-availability, which is a small fraction of the professional table.
     *
     * @param excludedProfessionalIds must never be empty — JPQL cannot express {@code NOT IN
     *                                 ()}. Callers pass a sentinel; see
     *                                 {@code SosMatchingService}.
     */
    @Query("SELECT new com.pronto.sos.dto.EligibleProfessional(p.id, p.userId, u.fullName, p.city, "
            + "p.serviceArea, p.basePrice, p.reliabilityScore, p.profileImageKey, "
            + "(SELECT AVG(r.rating) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id), "
            + "(SELECT COUNT(r) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id)) "
            + "FROM Professional p, com.pronto.users.entity.User u, "
            + "com.pronto.availability.entity.SosAvailability s "
            + "WHERE p.userId = u.id AND p.id = s.professionalId "
            + "AND p.categoryId = :categoryId AND s.isAvailable = true AND u.deletedAt IS NULL "
            + "AND " + ProfessionalEligibility.ELIGIBLE_JPQL + " "
            + "AND p.id NOT IN :excludedProfessionalIds")
    List<EligibleProfessional> findEligible(@Param("categoryId") Long categoryId,
                                              @Param("excludedProfessionalIds") List<Long> excludedProfessionalIds);
}
