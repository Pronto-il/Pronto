package com.pronto.sos.service;

import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.locations.service.ServiceCityResolver;
import com.pronto.matching.DistanceEtaStrategy;
import com.pronto.matching.EtaResult;
import com.pronto.matching.ServiceLocation;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.dto.EligibleProfessional;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.repository.SosCandidateRepository;
import com.pronto.sos.repository.SosOfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

/**
 * Finds and ranks the professionals an SOS request should be offered to.
 *
 * <p>Two distinct steps, deliberately not blurred together:
 * <ol>
 *   <li><b>Eligibility</b> — a hard filter in SQL ({@code SosCandidateRepository#findEligible}),
 *       plus the "already busy / already asked" exclusions computed here. Anything filtered out
 *       is never contacted, at any rank.</li>
 *   <li><b>Ranking</b> — a weighted score over the survivors, computed in Java, used only to
 *       decide <em>order</em> and to truncate to the configured pool size.</li>
 * </ol>
 *
 * <p><b>On not spamming everybody.</b> The pool cap ({@code pronto.sos.candidate-pool-size},
 * default 8; 15 for an {@code EMERGENCY}) is the structural answer: the eligibility query may
 * return every available plumber in the city, but only the top N are ever sent an offer.
 * Ranking is what makes that truncation defensible rather than arbitrary.
 *
 * <p><b>On the scoring model.</b> Five explicitly-weighted components, each normalized to
 * {@code [0, 1]} before weighting, summing to a score in {@code [0, 1]}. Linear, inspectable,
 * and every component's contribution is returned alongside the total so a ranking can be
 * explained after the fact. Deliberately not a learned model: there is no historical SOS data
 * to learn from yet — this feature is what will generate it — and an unexplainable ranking that
 * decides who gets paid is a bad trade for a marginal accuracy gain. The component set is
 * chosen so the intended future signals (SOS response speed, cancellation rate, category
 * expertise) slot in as additional weights without restructuring anything.
 */
@Service
public class SosMatchingService {

    private static final Logger log = LoggerFactory.getLogger(SosMatchingService.class);

    // ---- ranking weights ----
    //
    // These sum to 1.0. Starting points, chosen from what matters to a customer whose pipe is
    // currently leaking, in that order -- not measured optima. Tuning them against real
    // acceptance and completion data is the intended next step once this feature has run long
    // enough to produce any.

    /** How fast can they get here. The dominant factor in an urgent call, by some distance. */
    static final double WEIGHT_ETA = 0.40;

    /** How good are they. Second because a fast bad plumber is not a win. */
    static final double WEIGHT_RATING = 0.25;

    /** How reliably do they answer SOS calls at all — dispatching to a reliable responder is
     *  what keeps the customer from watching an empty candidate list for two minutes. */
    static final double WEIGHT_ACCEPTANCE = 0.15;

    /** How close are they, independent of ETA (which folds in traffic). Proximity is also a
     *  proxy for local knowledge and for a shorter trip if they need parts. */
    static final double WEIGHT_DISTANCE = 0.10;

    /** The platform's own pre-existing reliability figure on {@code professionals}. */
    static final double WEIGHT_RELIABILITY = 0.10;

    /**
     * ETA at or above this scores zero on the ETA component. Beyond roughly an hour the
     * differences stop mattering to someone in an emergency — they are all "too long".
     */
    static final int ETA_CEILING_MINUTES = 90;

    /** Distance at or above this scores zero on the distance component. */
    static final BigDecimal DISTANCE_CEILING_KM = new BigDecimal("50.0");

    /**
     * A professional with no rating yet scores this on the rating component — the midpoint,
     * not zero. An unrated professional is an unknown quantity, not a bad one, and starting
     * them at zero would make it structurally impossible for a new joiner to ever win a
     * dispatch and earn a first review.
     */
    static final double UNRATED_RATING_SCORE = 0.5;

    /** Likewise for acceptance rate: no SOS history means unknown, not bad. */
    static final double UNKNOWN_ACCEPTANCE_SCORE = 0.5;

    /** How far back acceptance-rate statistics are computed over. */
    static final Duration ACCEPTANCE_STATS_WINDOW = Duration.ofDays(30);

    /**
     * JPQL cannot express {@code NOT IN ()}, so an empty exclusion set is passed as this
     * sentinel instead. Ids are generated identity values starting at 1, so {@code -1} can
     * never match a real row.
     */
    private static final Long NO_EXCLUSIONS_SENTINEL = -1L;

    private final SosCandidateRepository sosCandidateRepository;
    /** Request city text -> canonical service_cities id. See findCandidates. */
    private final ServiceCityResolver serviceCityResolver;
    private final SosOfferRepository sosOfferRepository;
    private final DistanceEtaStrategy distanceEtaStrategy;
    private final SosProperties properties;

    public SosMatchingService(SosCandidateRepository sosCandidateRepository,
                               SosOfferRepository sosOfferRepository,
                               DistanceEtaStrategy distanceEtaStrategy,
                               ServiceCityResolver serviceCityResolver,
                               SosProperties properties) {
        this.serviceCityResolver = serviceCityResolver;
        this.sosCandidateRepository = sosCandidateRepository;
        this.sosOfferRepository = sosOfferRepository;
        this.distanceEtaStrategy = distanceEtaStrategy;
        this.properties = properties;
    }

    /**
     * The ranked, pool-capped list of professionals to offer {@code request} to, best first, at
     * the request's <em>current</em> search scope.
     *
     * <p>Overload for the initial wave, where the scope is derived from the request's own urgency
     * and no expansion has happened yet.
     */
    @Transactional(readOnly = true)
    public MatchingOutcome findCandidates(SosRequest request, Set<Long> alreadyOfferedProfessionalIds) {
        return findCandidates(request, alreadyOfferedProfessionalIds,
                SosSearchScope.initial(request.getUrgency(), properties));
    }

    /**
     * The ranked, scope-capped list of professionals to offer {@code request} to, best first.
     * Empty means nothing eligible was found — for the initial wave that is the caller's cue to
     * fail the request rather than leave the customer waiting on a dispatch that will never
     * arrive; for an expansion it simply means the wider scope turned up nobody new, which must
     * never terminate a request that already has candidates.
     *
     * <p><b>The pool cap is a total, not a per-wave allowance.</b> {@code scope.poolSize()} is
     * measured against everybody who already holds an offer on this request, so expanding from a
     * pool of 8 to 16 dispatches at most 8 more — a customer cannot press "סרוק שוב" twice and
     * fan out 24 fresh offers on top of the 8 that are already live.
     *
     * @param alreadyOfferedProfessionalIds professionals already sent an offer on this request
     *                                       by an earlier dispatch wave. Excluding them here is
     *                                       the first line of defence against duplicate offers;
     *                                       {@code ux_sos_offers_request_professional} is the
     *                                       authoritative one.
     * @param scope                         how wide to search — see {@link SosSearchScope} for
     *                                       what "wider" means in this implementation
     */
    @Transactional(readOnly = true)
    public MatchingOutcome findCandidates(SosRequest request, Set<Long> alreadyOfferedProfessionalIds,
                                            SosSearchScope scope) {
        Instant now = Instant.now();
        int remainingPoolSlots = scope.poolSize() - alreadyOfferedProfessionalIds.size();
        if (remainingPoolSlots <= 0) {
            log.info("sos.matching.pool-full sosRequestId={} scopeLevel={} poolSize={} alreadyOffered={}",
                    request.getId(), scope.level(), scope.poolSize(), alreadyOfferedProfessionalIds.size());
            return MatchingOutcome.empty();
        }

        // MS2: the destination the whole evaluation is measured to. Filled at request creation by
        // the client's own fix or by geocoding the service address (SosService). Absent means the
        // address never resolved -- which is a platform-side problem, not "nobody is nearby", and
        // must be reported as such rather than producing an empty candidate list.
        GeoCoordinates destination = GeoCoordinates.ofNullable(request.getLatitude(), request.getLongitude());
        if (destination == null) {
            log.warn("sos.matching.degraded sosRequestId={} reason=destination-unknown", request.getId());
            return MatchingOutcome.degraded(SosMatchingDegradation.DESTINATION_UNKNOWN);
        }

        List<Long> excluded = alreadyOfferedProfessionalIds.isEmpty()
                ? List.of(NO_EXCLUSIONS_SENTINEL)
                : new ArrayList<>(alreadyOfferedProfessionalIds);

        // The service-area filter, resolved to a canonical service_cities id before the query for
        // the same reason the standard listing does it there: eligibility is the professional's
        // declared coverage, not their base city and not how far away they happen to be.
        //
        // An unresolvable city is reported as a DEGRADED outcome rather than an empty one. The
        // distinction matters more here than anywhere else on the platform: "nobody is available"
        // tells a customer with an active leak to wait, while "we do not cover your area" tells
        // them to call somebody else, and only one of those is true when the address named a place
        // this platform has never heard of.
        Optional<Long> serviceCityId = serviceCityResolver.resolveId(request.getServiceCity());
        if (serviceCityId.isEmpty()) {
            log.warn("sos.matching.degraded sosRequestId={} reason=city-not-in-catalogue city=\"{}\"",
                    request.getId(), request.getServiceCity());
            return MatchingOutcome.degraded(SosMatchingDegradation.SERVICE_AREA_UNCOVERED);
        }

        List<EligibleProfessional> eligible = sosCandidateRepository.findEligible(
                request.getCategoryId(), serviceCityId.get(), excluded);
        if (eligible.isEmpty()) {
            log.info("sos.matching.empty sosRequestId={} categoryId={} serviceCityId={} "
                            + "reason=no-eligible-professionals",
                    request.getId(), request.getCategoryId(), serviceCityId.get());
            return MatchingOutcome.empty();
        }

        // Second exclusion pass, in Java rather than in the query above: a professional already
        // holding live offers on other requests is technically eligible for this one, but
        // dispatching them a third simultaneous urgent job mostly produces a slower response to
        // all three. One grouped query for the whole pool, not one per candidate.
        Set<Long> busy = new HashSet<>(sosOfferRepository.findProfessionalIdsWithLiveOffers(
                eligible.stream().map(EligibleProfessional::professionalId).toList(), now));
        List<EligibleProfessional> available = eligible.stream()
                .filter(p -> !busy.contains(p.professionalId()))
                .toList();

        // If excluding busy professionals would leave nobody, take them back rather than fail
        // the request. "Somebody who is busy" beats "nobody at all" for a customer with an
        // active leak -- this is a load-balancing preference, not an eligibility rule, and it
        // must not be allowed to masquerade as one.
        if (available.isEmpty()) {
            log.info("sos.matching.busy-fallback sosRequestId={} poolSize={} reason=all-candidates-busy",
                    request.getId(), eligible.size());
            available = eligible;
        }

        Map<Long, Double> acceptanceRates = loadAcceptanceRates(
                available.stream().map(EligibleProfessional::professionalId).toList(), now);

        // MS2: one batched routing call for the whole candidate set. Business filters have already
        // run -- category, marketplace eligibility, SOS availability, not-already-offered,
        // not-busy -- so only plausible candidates reach the provider. See the maps README's
        // call-budget section.
        List<Long> candidateIds = available.stream().map(EligibleProfessional::professionalId).toList();
        Map<Long, EtaResult> etaByProfessional =
                distanceEtaStrategy.calculateBatch(candidateIds, destination, now);

        BigDecimal maxRadius = scope.maxRadiusKm();
        List<RankedCandidate> ranked = new ArrayList<>();
        int excludedNoLocation = 0;
        int excludedProviderFailure = 0;
        int excludedOutsideRadius = 0;

        for (EligibleProfessional professional : available) {
            EtaResult eta = etaByProfessional.get(professional.professionalId());

            // ---- MS2's stricter SOS rule ----
            //
            // A professional without a sufficiently fresh, usable current position does NOT
            // participate in geographic SOS matching. Not approximated from their base city, not
            // given a default distance, not ranked with a neutral ETA score -- excluded.
            //
            // This is deliberately harsher than the normal marketplace listing, which still shows
            // such a professional with no ETA. The two flows are promising different things: a
            // standard listing is "book this person for Tuesday", where being unroutable right
            // now is irrelevant; SOS is "this person will reach you soon", which is a claim the
            // platform cannot make about somebody whose position it does not know. Dispatching
            // them anyway is how a customer with a burst pipe ends up waiting for a plumber who
            // was never nearby.
            if (eta == null || !eta.available()) {
                RouteUnavailableReason reason = eta == null
                        ? RouteUnavailableReason.PROVIDER_UNAVAILABLE
                        : eta.unavailableReason();
                if (reason.isProviderFailure()) {
                    excludedProviderFailure++;
                } else {
                    excludedNoLocation++;
                }
                continue;
            }

            // The geographic hard filter, now against a REAL road distance. This is what finally
            // makes SOS_MAX_DISPATCH_RADIUS_KM and the expansion multiplier mean something: the
            // pre-MS2 implementation returned 8 km same-city / 35 km otherwise against a 40 km
            // ceiling, so widening the ceiling changed nothing observable. The lifecycle around it
            // is untouched -- only the number being compared is now true.
            if (maxRadius != null && eta.distanceKm().compareTo(maxRadius) > 0) {
                excludedOutsideRadius++;
                continue;
            }

            ranked.add(score(professional, eta, acceptanceRates.get(professional.professionalId())));
        }

        if (ranked.isEmpty()) {
            // The distinction this branch draws is the whole of MS2's SOS failure policy. If every
            // candidate fell out because the provider could not answer, the honest statement is
            // "we could not evaluate anyone", NOT "nobody is within range" -- the second is a
            // claim about geography that the platform just demonstrated it cannot make.
            if (excludedProviderFailure > 0 && excludedOutsideRadius == 0 && excludedNoLocation == 0) {
                log.error("sos.matching.degraded sosRequestId={} categoryId={} reason=provider-unavailable "
                                + "candidates={}", request.getId(), request.getCategoryId(), available.size());
                return MatchingOutcome.degraded(SosMatchingDegradation.ROUTING_UNAVAILABLE);
            }
            log.info("sos.matching.empty sosRequestId={} categoryId={} reason=no-usable-candidates radiusKm={} "
                            + "noLocation={} providerFailure={} outsideRadius={}",
                    request.getId(), request.getCategoryId(), maxRadius,
                    excludedNoLocation, excludedProviderFailure, excludedOutsideRadius);
            return MatchingOutcome.empty();
        }

        List<RankedCandidate> pool = ranked.stream()
                .sorted(Comparator.comparing(RankedCandidate::score).reversed()
                        // Deterministic tie-break, so two runs over identical data produce
                        // identical dispatch order and a disputed ranking is reproducible.
                        .thenComparing(c -> c.professional().professionalId()))
                .limit(remainingPoolSlots)
                .toList();

        log.info("sos.matching.ranked sosRequestId={} scopeLevel={} eligible={} routable={} scored={} "
                        + "dispatching={} poolSize={} remainingSlots={} noLocation={} providerFailure={} "
                        + "outsideRadius={}",
                request.getId(), scope.level(), eligible.size(), available.size() - excludedNoLocation
                        - excludedProviderFailure, ranked.size(), pool.size(), scope.poolSize(),
                remainingPoolSlots, excludedNoLocation, excludedProviderFailure, excludedOutsideRadius);
        return MatchingOutcome.of(pool);
    }

    /** Exposed for the dispatcher, which needs the same address shape to price offers. */
    static ServiceLocation toServiceLocation(SosRequest request) {
        return new ServiceLocation(request.getServiceCity(), request.getServiceStreet(),
                request.getServiceHouseNumber(), request.getServiceApartment());
    }

    /**
     * Why one evaluation could produce nothing for a reason that is not about professionals.
     *
     * <p>Exists so that {@code SosDispatchService} can tell a customer the truth. Both values mean
     * "the platform could not do its job", never "no professional is available".
     */
    public enum SosMatchingDegradation {

        /** The routing provider could not be reached for any candidate. */
        ROUTING_UNAVAILABLE,

        /** The request's service address never resolved to coordinates, so nothing can be measured. */
        DESTINATION_UNKNOWN,

        /**
         * The request's city is not in this platform's {@code service_cities} catalogue, so no
         * professional's declared coverage can possibly include it.
         *
         * <p>A degradation rather than an empty result, deliberately. "Nobody is available right
         * now" invites a customer with an active leak to wait and retry; "we do not operate where
         * you are" tells them to call somebody else. Reporting the first when the second is true
         * is the most expensive lie this flow can tell.
         */
        SERVICE_AREA_UNCOVERED
    }

    /**
     * The result of one matching evaluation: candidates, or an explicit degradation.
     *
     * <p>Replaces a bare {@code List<RankedCandidate>}, and the reason is the same one that runs
     * through the rest of MS2: an empty list conflates "we asked and nobody qualifies" with "we
     * could not ask", and those two need opposite things said to the customer. A caller holding
     * this type has to decide which happened.
     */
    public record MatchingOutcome(List<RankedCandidate> candidates, SosMatchingDegradation degradation) {

        static MatchingOutcome of(List<RankedCandidate> candidates) {
            return new MatchingOutcome(candidates, null);
        }

        static MatchingOutcome empty() {
            return new MatchingOutcome(List.of(), null);
        }

        static MatchingOutcome degraded(SosMatchingDegradation degradation) {
            return new MatchingOutcome(List.of(), degradation);
        }

        public boolean isDegraded() {
            return degradation != null;
        }

        public boolean isEmpty() {
            return candidates.isEmpty();
        }
    }

    /**
     * The weighted sum. Each component is normalized to {@code [0, 1]} first, so the weights
     * above genuinely express relative importance rather than being distorted by the different
     * natural scales of minutes, kilometres and star ratings.
     */
    private RankedCandidate score(EligibleProfessional professional, EtaResult eta, Double acceptanceRate) {
        // MS2: eta is guaranteed available here -- findCandidates excludes every candidate without
        // a real route before scoring. The pre-MS2 version had to defend against a null distance
        // with a neutral 0.5, which was a quiet way of ranking somebody the platform could not
        // locate as merely average rather than as unknown. That branch is gone because the
        // situation it handled is now an exclusion, not a score.
        double etaScore = inverseNormalized(eta.etaMinutes(), ETA_CEILING_MINUTES);
        double distanceScore = inverseNormalized(eta.distanceKm().doubleValue(), DISTANCE_CEILING_KM.doubleValue());
        // reviews.rating is 1-5; map onto [0,1] by (r-1)/4 so a 1-star is genuinely zero rather
        // than 0.2 -- the bottom of the scale should score bottom.
        double ratingScore = professional.averageRating() == null
                ? UNRATED_RATING_SCORE
                : clamp((professional.averageRating() - 1.0) / 4.0);
        double acceptanceScore = acceptanceRate == null ? UNKNOWN_ACCEPTANCE_SCORE : clamp(acceptanceRate);
        // professionals.reliability_score is NUMERIC(3,2), i.e. already 0..1 by column shape.
        double reliabilityScore = professional.reliabilityScore() == null
                ? 0.5
                : clamp(professional.reliabilityScore().doubleValue());

        Map<String, Double> components = new LinkedHashMap<>();
        components.put("eta", etaScore * WEIGHT_ETA);
        components.put("rating", ratingScore * WEIGHT_RATING);
        components.put("acceptance", acceptanceScore * WEIGHT_ACCEPTANCE);
        components.put("distance", distanceScore * WEIGHT_DISTANCE);
        components.put("reliability", reliabilityScore * WEIGHT_RELIABILITY);

        double total = components.values().stream().mapToDouble(Double::doubleValue).sum();

        return new RankedCandidate(professional,
                // 3 decimal places, matching sos_offers.match_score's NUMERIC(6,3).
                BigDecimal.valueOf(total).setScale(3, RoundingMode.HALF_UP),
                eta.distanceKm(), eta.etaMinutes(), components);
    }

    /**
     * Acceptance rate per professional over {@link #ACCEPTANCE_STATS_WINDOW}. Absent from the
     * map when the professional has no SOS history in the window — the caller distinguishes
     * that from a genuine zero, since "never asked" and "always declines" deserve very
     * different treatment.
     */
    private Map<Long, Double> loadAcceptanceRates(List<Long> professionalIds, Instant now) {
        if (professionalIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Double> rates = new HashMap<>();
        for (Object[] row : sosOfferRepository.findAcceptanceStats(
                professionalIds, now.minus(ACCEPTANCE_STATS_WINDOW))) {
            Long professionalId = ((Number) row[0]).longValue();
            long offered = ((Number) row[1]).longValue();
            long accepted = row[2] == null ? 0L : ((Number) row[2]).longValue();
            if (offered > 0) {
                rates.put(professionalId, (double) accepted / offered);
            }
        }
        return rates;
    }

    /** {@code 1.0} at zero, falling linearly to {@code 0.0} at {@code ceiling} and beyond. */
    private static double inverseNormalized(double value, double ceiling) {
        if (ceiling <= 0) {
            return 0.0;
        }
        return clamp(1.0 - (value / ceiling));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
