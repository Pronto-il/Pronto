package com.pronto.bookings.repository;

import com.pronto.bookings.dto.ProfessionalCard;
import com.pronto.professionals.ProfessionalCategoryMatch;
import com.pronto.professionals.ProfessionalEligibility;
import com.pronto.professionals.ProfessionalServiceAreaMatch;
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
     * §2.2 step 6: {@code professionals} joined to {@code users}, restricted to professionals
     * who serve {@code issue.categoryId}, with {@code users.deleted_at IS NULL}, ordered by
     * {@code base_price ASC} (cheapest first — judgment call, §7 of the contract doc).
     *
     * <p><b>MS1:</b> additionally filtered by {@link ProfessionalEligibility#ELIGIBLE_JPQL} —
     * approval plus completed onboarding, concatenated from the one constant that also drives
     * {@code SosCandidateRepository.findEligible} and
     * {@code ProfessionalRepository.existsEligibleById}. This is the customer's discovery
     * surface, and MS0 recorded that it had no approval filter of any kind. The
     * {@code u.deletedAt IS NULL} clause is left where it already was, outside the fragment, per
     * that constant's alias/scope contract.
     *
     * <p><b>Service-area filter (the Eilat fix):</b> additionally filtered by
     * {@link ProfessionalServiceAreaMatch#SERVES_CITY_JPQL}. Until it was added this query applied
     * <em>no geographic predicate at all</em> — a customer in Eilat was served every eligible
     * professional in the country, and the only thing their address changed was the ETA number
     * printed on each card. {@code :serviceCityId} is a canonical {@code service_cities} id
     * resolved by {@code locations.service.ServiceCityResolver} before this query is reached, and
     * is never null: {@code BookingsService} short-circuits an unresolvable city to an empty
     * listing rather than binding null and relying on SQL's comparison semantics to filter
     * everything out by accident.
     *
     * <p><b>MS4:</b> the category filter is no longer {@code p.categoryId = :categoryId} — a
     * professional holds a <em>set</em> of categories now, and is eligible if the requested one
     * is anywhere in it, not only if it is their first. The membership test is concatenated from
     * {@link ProfessionalCategoryMatch#SERVES_CATEGORY_JPQL}, the same constant the SOS hard
     * filter uses, so the two surfaces cannot answer "does this plumber-and-handyman serve
     * handyman work?" differently. The region/base-city labels are joined in from the closed
     * {@code service_regions}/{@code service_cities} catalogue via {@code LEFT JOIN} — left,
     * because {@code V44} leaves both ids null on any pre-MS4 row whose free text named no
     * recognisable place, and dropping those professionals out of the listing would be a
     * de-listing this migration explicitly refused to perform.
     */
    @Query("SELECT new com.pronto.bookings.dto.ProfessionalCard(p.id, u.fullName, sr.nameHe, "
            + "p.basePrice, p.reliabilityScore, sc.nameHe, p.profileImageKey, "
            + "(SELECT AVG(r.rating) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id), "
            + "(SELECT COUNT(r) FROM com.pronto.reviews.entity.Review r WHERE r.professionalId = p.id), "
            + "(SELECT COUNT(f) FROM com.pronto.favorites.entity.Favorite f "
            + "WHERE f.customerId = :customerId AND f.professionalId = p.id)) "
            + "FROM Professional p "
            + "JOIN com.pronto.users.entity.User u ON u.id = p.userId "
            + "LEFT JOIN com.pronto.locations.entity.ServiceRegion sr ON sr.id = p.serviceRegionId "
            + "LEFT JOIN com.pronto.locations.entity.ServiceCity sc ON sc.id = p.baseCityId "
            + "WHERE u.deletedAt IS NULL "
            + "AND " + ProfessionalCategoryMatch.SERVES_CATEGORY_JPQL + " "
            + "AND " + ProfessionalEligibility.ELIGIBLE_JPQL + " "
            + "AND " + ProfessionalServiceAreaMatch.SERVES_CITY_JPQL + " "
            + "ORDER BY p.basePrice ASC")
    List<ProfessionalCard> listByCategoryAndServiceCity(@Param("categoryId") Long categoryId,
                                                          @Param("customerId") Long customerId,
                                                          @Param("serviceCityId") Long serviceCityId);

}
