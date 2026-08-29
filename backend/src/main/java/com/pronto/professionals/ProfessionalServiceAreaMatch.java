package com.pronto.professionals;

/**
 * <b>The single definition of "this professional serves the city the customer is in."</b>
 *
 * <h2>What this fixes</h2>
 *
 * <p>Nothing enforced it. {@code bookings.repository.ProfessionalListingRepository.listByCategory}
 * and {@code sos.repository.SosCandidateRepository.findEligible} filtered on category, approval,
 * onboarding and phone verification — and on <b>no geography at all</b>. A customer in Eilat was
 * shown every eligible professional in the country; the only thing that varied by address was the
 * ETA number printed on each card, computed after the fact by {@code matching}. So the listing
 * answered "who does this work?" while appearing to answer "who does this work, near me?".
 *
 * <p>{@code V44} anticipated this exactly — it created
 * {@code idx_professional_service_cities_city} with the comment "this is the other direction, 'who
 * serves this city', which a future city-scoped filter reads." This is that filter.
 *
 * <h2>Coverage, not base city, and not distance</h2>
 *
 * <p>The deciding fact is membership in {@code professional_service_cities} — the set of places the
 * professional said they are willing to travel to. {@code professionals.base_city_id} is where they
 * are based and is deliberately <b>not</b> consulted here: a Tel Aviv-based professional who lists
 * Eilat among their service cities is eligible for an Eilat job, and one who lists only Tel Aviv,
 * Ramat Gan and Givatayim is not — however close their base city happens to be to anything.
 *
 * <p>Distance and ETA are also not consulted, and must not become a substitute. They are computed
 * from a live device position that may be anywhere at the moment of the query, they degrade to
 * "unavailable" whenever the routing provider is down, and "40 km away right now" is a different
 * claim from "I work there". Coverage is a standing declaration by the professional; distance is
 * an observation about a Tuesday afternoon.
 *
 * <h2>Alias contract</h2>
 *
 * <p>A bare boolean JPQL fragment, not a complete query, with exactly the contract
 * {@link ProfessionalCategoryMatch#SERVES_CATEGORY_JPQL} and
 * {@link ProfessionalEligibility#ELIGIBLE_JPQL} already use: <b>the alias {@code p} must be bound
 * to {@link com.pronto.professionals.entity.Professional}</b> in the host query, which must also
 * bind a {@code :serviceCityId} parameter. Join it with {@code AND}.
 *
 * <p>The subquery alias is {@code pscMatch}, distinct from the {@code pcMatch} and
 * {@code pcOnboarding} the sibling fragments use, so all three may be concatenated into one
 * {@code WHERE} clause without shadowing each other.
 *
 * <h2>{@code :serviceCityId} must never be null</h2>
 *
 * <p>Binding null would make the {@code EXISTS} match nothing — the right answer by accident, via
 * SQL's null comparison semantics rather than via a decision anyone wrote down. Callers resolve
 * the city first ({@code locations.service.ServiceCityResolver}) and short-circuit to an empty
 * result when it cannot be resolved, so "we do not cover this place" is an explicit branch with a
 * log line rather than an empty result set nobody can explain.
 *
 * <p>Index-anchored on {@code idx_professional_service_cities_city}; the composite primary key is
 * ordered {@code (professional_id, city_id)} and cannot serve a city-first lookup.
 */
public final class ProfessionalServiceAreaMatch {

    /**
     * "{@code p}'s configured service coverage includes {@code :serviceCityId}". See this class's
     * Javadoc for the alias contract and for why base city is not part of it.
     */
    public static final String SERVES_CITY_JPQL =
            "EXISTS (SELECT 1 FROM com.pronto.professionals.entity.ProfessionalServiceCity pscMatch "
            + "WHERE pscMatch.professionalId = p.id AND pscMatch.cityId = :serviceCityId)";

    private ProfessionalServiceAreaMatch() {
    }
}
