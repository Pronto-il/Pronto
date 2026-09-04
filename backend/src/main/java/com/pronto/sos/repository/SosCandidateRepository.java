package com.pronto.sos.repository;

import com.pronto.professionals.ProfessionalCategoryMatch;
import com.pronto.professionals.ProfessionalEligibility;
import com.pronto.professionals.ProfessionalServiceAreaMatch;
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
     *   <li><b>{@link ProfessionalCategoryMatch#SERVES_CATEGORY_JPQL}</b> — a plumber cannot
     *       take an electrical job. Not negotiable. <b>MS4</b> makes this a membership test over
     *       {@code professional_categories} instead of the old {@code p.categoryId =
     *       :categoryId} column comparison: a professional who serves
     *       {@code [Plumbing, Handyman]} is askable for both, and neither is privileged over the
     *       other. Concatenated from the same constant
     *       {@code bookings.repository.ProfessionalListingRepository} uses, so the SOS hard
     *       filter and standard discovery cannot disagree about who serves what — the failure
     *       mode being a professional a customer can find by browsing but SOS will never
     *       dispatch to.</li>
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
     *   <li><b>{@link ProfessionalServiceAreaMatch#SERVES_CITY_JPQL}</b> — the professional's own
     *       declared coverage includes the request's city. Added with the standard listing's
     *       identical filter, from the same constant, because SOS had the same gap: eligibility
     *       was category + live availability + approval, and a Gush Dan professional was a
     *       candidate for an Eilat emergency. The radius filter applied later in
     *       {@code SosMatchingService} <em>usually</em> caught that, which is exactly why it went
     *       unnoticed — but radius is measured from a live device position that may be anywhere,
     *       degrades to "unavailable" when the routing provider is down, and answers a different
     *       question from "do you work there". Coverage is the rule; distance is a ranking and
     *       range concern layered on top of it.</li>
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
    @Query("SELECT new com.pronto.sos.dto.EligibleProfessional(p.id, p.userId, u.fullName, sc.nameHe, "
            + "sr.nameHe, p.basePrice, p.reliabilityScore, p.profileImageKey, "
            + "(SELECT AVG(r.rating) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id), "
            + "(SELECT COUNT(r) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id)) "
            + "FROM Professional p "
            + "JOIN com.pronto.users.entity.User u ON u.id = p.userId "
            + "JOIN com.pronto.availability.entity.SosAvailability s ON s.professionalId = p.id "
            + "LEFT JOIN com.pronto.locations.entity.ServiceRegion sr ON sr.id = p.serviceRegionId "
            + "LEFT JOIN com.pronto.locations.entity.ServiceCity sc ON sc.id = p.baseCityId "
            + "WHERE s.isAvailable = true AND u.deletedAt IS NULL "
            + "AND " + ProfessionalCategoryMatch.SERVES_CATEGORY_JPQL + " "
            + "AND " + ProfessionalEligibility.ELIGIBLE_JPQL + " "
            + "AND " + ProfessionalServiceAreaMatch.SERVES_CITY_JPQL + " "
            + "AND p.id NOT IN :excludedProfessionalIds")
    List<EligibleProfessional> findEligible(@Param("categoryId") Long categoryId,
                                              @Param("serviceCityId") Long serviceCityId,
                                              @Param("excludedProfessionalIds") List<Long> excludedProfessionalIds);

    /**
     * <b>The demo SOS presenter, if one exists.</b> A deliberately separate query from
     * {@link #findEligible} above, and the separation is the safety property — not a stylistic
     * choice.
     *
     * <p>The alternative was an {@code OR} branch inside {@code findEligible}. That would have put
     * a demo concept inside the single statement that decides who may be dispatched to in
     * Production, where a mistake in the parenthesisation of one {@code OR} silently widens the
     * production filter. Here, the production query is byte-for-byte what it was, this one is
     * called only after {@code demo.DemoBehaviorPolicy#isAllowed()} has returned {@code true}, and
     * the two results are merged in Java where the merge is visible and testable.
     *
     * <h2>What is relaxed, and what is emphatically not</h2>
     *
     * Relaxed — every filter that could keep the presenter out of a dispatch:
     * <ul>
     *   <li><b>category</b> — so one presenter can demonstrate SOS for any trade, per the demo
     *       requirement, without being given eight trades in {@code professional_categories} (which
     *       would make them a universal professional in ordinary browse-and-book listings too;
     *       those read {@code ProfessionalListingRepository}, which this query is not part of and
     *       does not affect);</li>
     *   <li><b>declared service city</b> — a demonstration is not always run against an address in
     *       the presenter's real coverage;</li>
     *   <li><b>the live SOS availability toggle</b> — no {@code sos_availability} join at all, so a
     *       presenter who left the toggle off still receives the request. This is what makes "I do
     *       not have to touch the database before a demo" true;</li>
     *   <li><b>onboarding eligibility</b> — {@link ProfessionalEligibility#ELIGIBLE_JPQL} is
     *       deliberately <em>not</em> applied here. It used to be, on the reasoning that a presenter
     *       should be "an ordinary professional who is asked more often". That reasoning conflicts
     *       with the guarantee this query now has to make: approval status, a verification
     *       document, enabled working hours, a sub-service row and phone verification are five
     *       independent ways for the presenter to silently stop receiving requests between
     *       presentations, and diagnosing which one fired is exactly the pre-demo database
     *       archaeology the flag exists to abolish;</li>
     *   <li>and, in {@code SosMatchingService}, the dispatch radius, the requirement for a fresh
     *       routable device position, the busy-professional filter, the pool cap, an unresolvable
     *       service city and an ungeocodable address.</li>
     * </ul>
     *
     * <b>Not</b> relaxed — two things, both about the row existing at all rather than about
     * matching: {@code p.demoSosPresenter = true} itself, and {@code u.deletedAt IS NULL}. A
     * soft-deleted account is not a professional who is hard to match, it is one who is gone, and
     * dispatching a real customer's emergency to a deleted account is not a demo behaviour anybody
     * asked for.
     *
     * <p><b>This query grants no permissions.</b> Being dispatched an offer is not the same as
     * being given work. {@code SosService}'s selection path re-checks
     * {@link ProfessionalEligibility} through {@code ProfessionalRepository#existsEligibleById} at
     * the last moment before an order exists, and that check is untouched by anything here. So an
     * un-onboarded presenter is <em>offered</em> every SOS request, may <em>accept</em> the offer
     * ({@code SosOfferService#accept} is ungated by design), and is then refused with
     * {@code SOS_CANDIDATE_NOT_AVAILABLE} if the customer actually selects them — no order, no
     * priced commitment. A presenter who is expected to complete a booking on stage therefore
     * still needs to be properly onboarded; what this relaxation buys is that the <em>request
     * always arrives</em>, which is the part a demonstration depends on.
     *
     * <p>Returns a list rather than an {@code Optional} only so the caller can merge it without a
     * special case; {@code ux_professionals_demo_sos_presenter} ({@code V56}) makes it at most one
     * row.
     *
     * @param excludedProfessionalIds same contract as {@link #findEligible} — never empty, and it
     *                                is what stops the presenter being sent a second offer on a
     *                                request they already hold one for (they are in
     *                                {@code alreadyOffered} from the first wave onward)
     */
    @Query("SELECT new com.pronto.sos.dto.EligibleProfessional(p.id, p.userId, u.fullName, sc.nameHe, "
            + "sr.nameHe, p.basePrice, p.reliabilityScore, p.profileImageKey, "
            + "(SELECT AVG(r.rating) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id), "
            + "(SELECT COUNT(r) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id)) "
            + "FROM Professional p "
            + "JOIN com.pronto.users.entity.User u ON u.id = p.userId "
            + "LEFT JOIN com.pronto.locations.entity.ServiceRegion sr ON sr.id = p.serviceRegionId "
            + "LEFT JOIN com.pronto.locations.entity.ServiceCity sc ON sc.id = p.baseCityId "
            + "WHERE p.demoSosPresenter = true AND u.deletedAt IS NULL "
            + "AND p.id NOT IN :excludedProfessionalIds")
    List<EligibleProfessional> findDemoSosPresenters(
            @Param("excludedProfessionalIds") List<Long> excludedProfessionalIds);
}
