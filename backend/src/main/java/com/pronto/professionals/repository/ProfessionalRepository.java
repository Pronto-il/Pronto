package com.pronto.professionals.repository;

import com.pronto.professionals.ProfessionalEligibility;
import com.pronto.professionals.entity.Professional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProfessionalRepository extends JpaRepository<Professional, Long> {

    Optional<Professional> findByUserId(Long userId);

    /**
     * <b>The single-row form of {@link ProfessionalEligibility#ELIGIBLE_JPQL}</b>, built from
     * that exact constant so a service-level guard and the listing/dispatch queries can never
     * drift apart. Every service that has to answer "may this specific professional be given new
     * work?" — {@code BookingsService} (available windows, order creation),
     * {@code SosService#selectProfessional}, {@code FavoritesService#addFavorite},
     * {@code ProfessionalsService}/{@code AvailabilityService}'s {@code bookable} signal —
     * delegates here rather than re-implementing the rule in Java. That drift, between the SQL
     * filter and a hand-written Java check, is the realistic failure mode; drift between two
     * {@code @Query} strings built from the same constant is not.
     *
     * <p>Deliberately does <b>not</b> include {@code users.deleted_at IS NULL} — see
     * {@link ProfessionalEligibility}'s Javadoc for why that stays adjacent. Callers that need it
     * apply it themselves.
     *
     * @return {@code false} for a professional id that does not exist at all, which is what every
     *         caller wants: "not bookable" and "not there" are the same answer to this question.
     */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Professional p "
            + "WHERE p.id = :professionalId AND " + ProfessionalEligibility.ELIGIBLE_JPQL)
    boolean existsEligibleById(@Param("professionalId") Long professionalId);

    /**
     * The onboarding half of the same rule, for the operator review screen only — built from
     * {@link ProfessionalEligibility#ONBOARDING_COMPLETE_JPQL} for exactly the reason
     * {@link #existsEligibleById} is built from the full constant. Never a gate: no request is
     * allowed or refused on this answer, because completed onboarding without approval is still
     * not bookable. It exists so an operator can see that approving this person would leave them
     * non-bookable <em>before</em> deciding, rather than discovering it afterwards.
     */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Professional p "
            + "WHERE p.id = :professionalId AND " + ProfessionalEligibility.ONBOARDING_COMPLETE_JPQL)
    boolean hasCompleteOnboarding(@Param("professionalId") Long professionalId);

    /** MS1 operator queue: the professionals awaiting (or holding) a given decision, oldest first. */
    List<Professional> findByApprovalStatusOrderByCreatedAtAsc(String approvalStatus);

    /** MS1 operator queue, unfiltered. */
    List<Professional> findAllByOrderByCreatedAtAsc();
}
