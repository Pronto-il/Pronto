package com.pronto.sos.service;

import com.pronto.maps.RouteUnavailableReason;
import com.pronto.matching.DistanceEtaStrategy;
import com.pronto.matching.EtaResult;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.dto.EligibleProfessional;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.repository.SosCandidateRepository;
import com.pronto.sos.repository.SosOfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Eligibility filtering, the pool cap, and the ranking model.
 *
 * <p><b>Production MS2</b> rewrote how distance enters this service. These tests used to stub
 * {@code calculate(city, location, time)} -- a per-candidate call keyed on a city string -- and now
 * stub {@code calculateBatch(ids, destination, time)}, keyed on professional id, because that is
 * what real batched routing looks like. Two rules that could not be expressed at all before have
 * their own tests at the bottom: a professional with no usable current position is excluded from
 * SOS entirely, and a total provider failure is reported as a degradation rather than as "nobody is
 * nearby".
 */
class SosMatchingServiceTest {

    private static final Long CATEGORY_ID = 7L;

    private SosCandidateRepository sosCandidateRepository;
    private SosOfferRepository sosOfferRepository;
    private DistanceEtaStrategy distanceEtaStrategy;
    private SosProperties properties;
    private static final Long SERVICE_CITY_ID = 4001L;
    private com.pronto.locations.service.ServiceCityResolver serviceCityResolver;
    private SosMatchingService service;

    @BeforeEach
    void setUp() {
        sosCandidateRepository = Mockito.mock(SosCandidateRepository.class);
        sosOfferRepository = Mockito.mock(SosOfferRepository.class);
        distanceEtaStrategy = Mockito.mock(DistanceEtaStrategy.class);
        properties = new SosProperties();
        serviceCityResolver = Mockito.mock(com.pronto.locations.service.ServiceCityResolver.class);
        // Resolved by default: these tests are about ranking and pool size, not coverage. The
        // service-area tests below override it.
        Mockito.lenient().when(serviceCityResolver.resolveId(any()))
                .thenReturn(java.util.Optional.of(SERVICE_CITY_ID));
        service = new SosMatchingService(sosCandidateRepository, sosOfferRepository, distanceEtaStrategy,
                serviceCityResolver, properties);

        when(sosOfferRepository.findProfessionalIdsWithLiveOffers(anyList(), any())).thenReturn(List.of());
        when(sosOfferRepository.findAcceptanceStats(anyList(), any())).thenReturn(List.of());
        // Default: every candidate is routable, 8 km / 15 minutes away. Answering per-id from the
        // requested set (rather than from a fixed map) keeps every pre-existing pool and ranking
        // test working without each one having to enumerate its own professionals.
        when(distanceEtaStrategy.calculateBatch(any(), any(), any()))
                .thenAnswer(inv -> uniformEta(inv.getArgument(0), new BigDecimal("8.0"), 15));
    }

    /**
     * MS2: an SOS request now carries resolved destination coordinates -- Dizengoff 10, Tel Aviv.
     * Without them there is nothing to measure a distance to, and matching refuses to pretend
     * otherwise (see {@code aRequestWithNoResolvedDestinationIsDegradedNotEmpty}).
     */
    private static SosRequest request(SosUrgency urgency) {
        SosRequest request = new SosRequest(1L, 2L, CATEGORY_ID, null, "leak", urgency,
                "Tel Aviv", "Dizengoff", "10", null, null, null, null,
                new BigDecimal("32.077000"), new BigDecimal("34.773900"));
        setField(request, "id", 100L);
        return request;
    }

    /**
     * Every requested id, same figures -- the "geography is not what this test is about" default.
     *
     * <p>{@code ids} is null-tolerant on purpose. Re-stubbing a Mockito mock calls the method
     * again with null arguments to record the new stub, which re-enters this answer; without the
     * guard, every test that overrides the default in {@code setUp} fails inside the override
     * itself rather than in the code under test.
     */
    private static Map<Long, EtaResult> uniformEta(java.util.Collection<Long> ids, BigDecimal km, int minutes) {
        Map<Long, EtaResult> result = new LinkedHashMap<>();
        if (ids == null) {
            return result;
        }
        ids.forEach(id -> result.put(id, EtaResult.available(km, minutes, true)));
        return result;
    }

    private static EligibleProfessional professional(long id, Double rating, long reviews, String basePrice) {
        return new EligibleProfessional(id, 1000 + id, "Pro " + id, "Tel Aviv", "Center",
                new BigDecimal(basePrice), null, null, rating, reviews);
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            var field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void returnsEmptyWhenNobodyIsEligible() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList())).thenReturn(List.of());

        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates()).isEmpty();
    }

    /** JPQL cannot express {@code NOT IN ()}, so an empty exclusion set must become a sentinel. */
    @Test
    void emptyExclusionSetIsPassedAsANonEmptySentinel() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList())).thenReturn(List.of());

        service.findCandidates(request(SosUrgency.URGENT), Set.of());

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(sosCandidateRepository).findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), captor.capture());
        assertThat(captor.getValue()).isNotEmpty().allMatch(id -> id < 0);
    }

    @Test
    void alreadyOfferedProfessionalsAreExcluded() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList())).thenReturn(List.of());

        service.findCandidates(request(SosUrgency.URGENT), Set.of(5L, 9L));

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(sosCandidateRepository).findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(5L, 9L);
    }

    @Test
    void professionalsAlreadyHoldingLiveOffersAreDroppedFromThePool() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250"), professional(2, 5.0, 10, "250")));
        when(sosOfferRepository.findProfessionalIdsWithLiveOffers(anyList(), any())).thenReturn(List.of(1L));

        List<RankedCandidate> candidates = service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates();

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).professional().professionalId()).isEqualTo(2L);
    }

    /**
     * "Somebody who is busy" beats "nobody at all" for a customer with an active leak — the
     * busy filter is a load-balancing preference, not an eligibility rule.
     */
    @Test
    void allCandidatesBusyFallsBackToDispatchingThemAnyway() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250")));
        when(sosOfferRepository.findProfessionalIdsWithLiveOffers(anyList(), any())).thenReturn(List.of(1L));

        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates()).hasSize(1);
    }

    @Test
    void candidatesBeyondTheDispatchRadiusAreExcluded() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250")));
        // 200 real kilometres, well past the 40 km default cap. Before MS2 this filter was inert:
        // the only distances the platform could produce were 8 and 35 km, so a 40 km ceiling
        // excluded nobody. It is now a genuine radius.
        when(distanceEtaStrategy.calculateBatch(any(), any(), any()))
                .thenAnswer(inv -> uniformEta(inv.getArgument(0), new BigDecimal("200.0"), 120));

        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates()).isEmpty();
    }

    @Test
    void poolIsCappedAtTheConfiguredSizeForAnUrgentRequest() {
        properties.setCandidatePoolSize(3);
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(IntStream.rangeClosed(1, 20)
                        .mapToObj(i -> professional(i, 5.0, 10, "250"))
                        .toList());

        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates()).hasSize(3);
    }

    /** An emergency accepts more professional interruption for a better chance of an answer. */
    @Test
    void emergencyUsesTheWiderPool() {
        properties.setCandidatePoolSize(3);
        properties.setEmergencyCandidatePoolSize(7);
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(IntStream.rangeClosed(1, 20)
                        .mapToObj(i -> professional(i, 5.0, 10, "250"))
                        .toList());

        assertThat(service.findCandidates(request(SosUrgency.EMERGENCY), Set.of()).candidates()).hasSize(7);
    }

    // ---- expansion scope ----

    /**
     * <b>The pool cap is a running total across every wave.</b> An expansion from 3 to 6 may
     * contact three more, not six more — otherwise pressing "סרוק שוב" twice would fan out far
     * more offers than the configured ceiling for one job.
     */
    @Test
    void anExpansionOnlyDispatchesTheDifferenceBetweenTheOldPoolAndTheNew() {
        properties.setCandidatePoolSize(3);
        properties.setExpansionPoolIncrement(3);
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(IntStream.rangeClosed(4, 20)
                        .mapToObj(i -> professional(i, 5.0, 10, "250"))
                        .toList());

        SosSearchScope expanded = SosSearchScope.forLevel(1, SosUrgency.URGENT, properties);
        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of(1L, 2L, 3L), expanded).candidates())
                .hasSize(3);
    }

    /**
     * The pool is already full at this level — the customer widened, but everybody the wider
     * scope allows has already been asked. Returning nothing is correct, and the caller treats it
     * as an ordinary empty expansion rather than a failure.
     */
    @Test
    void anExpansionWhosePoolIsAlreadyFullContactsNobody() {
        properties.setCandidatePoolSize(3);
        properties.setExpansionPoolIncrement(1);
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(IntStream.rangeClosed(10, 20)
                        .mapToObj(i -> professional(i, 5.0, 10, "250"))
                        .toList());

        SosSearchScope expanded = SosSearchScope.forLevel(1, SosUrgency.URGENT, properties);
        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of(1L, 2L, 3L, 4L), expanded).candidates())
                .isEmpty();
    }

    /**
     * Eligibility is a hard filter at <em>every</em> scope level. Widening the search asks more
     * people; it never makes somebody askable who should not have been asked at all — so the
     * excluded set is still handed to the query on an expansion.
     */
    @Test
    void expandingStillExcludesEveryoneAlreadyOffered() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(9, 5.0, 10, "250")));

        service.findCandidates(request(SosUrgency.URGENT), Set.of(1L, 2L),
                SosSearchScope.forLevel(2, SosUrgency.URGENT, properties));

        ArgumentCaptor<List<Long>> excluded = ArgumentCaptor.forClass(List.class);
        Mockito.verify(sosCandidateRepository).findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), excluded.capture());
        assertThat(excluded.getValue()).containsExactlyInAnyOrder(1L, 2L);
    }

    /** ETA is the dominant weight, so with everything else equal the faster professional wins. */
    @Test
    void fasterEtaOutranksSlowerWhenAllElseIsEqual() {
        EligibleProfessional slow = professional(1, 5.0, 10, "250");
        EligibleProfessional fast = professional(2, 5.0, 10, "250");
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList())).thenReturn(List.of(slow, fast));
        when(distanceEtaStrategy.calculateBatch(any(), any(), any())).thenReturn(Map.of(
                1L, EtaResult.available(new BigDecimal("30.0"), 70, true),
                2L, EtaResult.available(new BigDecimal("5.0"), 10, true)));

        List<RankedCandidate> ranked = service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates();

        assertThat(ranked.get(0).professional().professionalId()).isEqualTo(2L);
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
    }

    /** With identical ETA, the better-rated professional wins. */
    @Test
    void higherRatingOutranksLowerWhenEtaIsEqual() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 2.0, 40, "250"), professional(2, 5.0, 40, "250")));

        List<RankedCandidate> ranked = service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates();

        assertThat(ranked.get(0).professional().professionalId()).isEqualTo(2L);
    }

    /**
     * A new joiner with no reviews must not be structurally unable to ever win a dispatch —
     * unrated scores the midpoint, so they beat a genuinely badly-rated professional.
     */
    @Test
    void unratedProfessionalScoresAboveABadlyRatedOne() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 1.0, 30, "250"), professional(2, null, 0, "250")));

        List<RankedCandidate> ranked = service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates();

        assertThat(ranked.get(0).professional().professionalId()).isEqualTo(2L);
    }

    @Test
    void acceptanceRateRaisesTheScore() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250"), professional(2, 5.0, 10, "250")));
        // Professional 1 accepted 1 of 10; professional 2 accepted 10 of 10.
        when(sosOfferRepository.findAcceptanceStats(anyList(), any())).thenReturn(List.of(
                new Object[]{1L, 10L, 1L},
                new Object[]{2L, 10L, 10L}));

        List<RankedCandidate> ranked = service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates();

        assertThat(ranked.get(0).professional().professionalId()).isEqualTo(2L);
    }

    @Test
    void scoresStayWithinZeroToOneAndComponentsSumToTheTotal() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 4.2, 17, "250")));

        RankedCandidate candidate = service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates().get(0);

        assertThat(candidate.score()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        double componentSum = candidate.componentScores().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(candidate.score().doubleValue()).isCloseTo(componentSum, org.assertj.core.data.Offset.offset(0.001));
        assertThat(candidate.componentScores())
                .containsOnlyKeys("eta", "rating", "acceptance", "distance", "reliability");
    }

    /** Two runs over identical data must produce identical dispatch order. */
    @Test
    void rankingIsDeterministicForTiedScores() {
        List<EligibleProfessional> pool = List.of(
                professional(3, 5.0, 10, "250"),
                professional(1, 5.0, 10, "250"),
                professional(2, 5.0, 10, "250"));
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList())).thenReturn(pool);

        List<Long> first = service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates().stream()
                .map(c -> c.professional().professionalId()).toList();
        List<Long> second = service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates().stream()
                .map(c -> c.professional().professionalId()).toList();

        assertThat(first).isEqualTo(second).containsExactly(1L, 2L, 3L);
    }

    @Test
    void nullReliabilityAndNullBasePriceDoNotBreakScoring() {
        EligibleProfessional incomplete = new EligibleProfessional(1L, 11L, "Pro", "Tel Aviv", "Center",
                null, null, null, null, 0L);
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList())).thenReturn(List.of(incomplete));

        List<RankedCandidate> ranked = service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates();

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).score()).isNotNull();
    }

    // ---------------------------------------------------------------------------------------
    // Production MS2 -- real geography
    // ---------------------------------------------------------------------------------------

    /**
     * <b>The stricter SOS rule.</b> A professional without a sufficiently fresh, usable position
     * does not participate in geographic SOS matching at all -- not approximated from their base
     * city, not given a neutral ETA score, not dispatched.
     *
     * <p>Deliberately harsher than the normal marketplace listing, which still shows such a
     * professional with no ETA. The two flows promise different things: a standard listing is
     * "book this person for Tuesday", where being unroutable right now is irrelevant; SOS is
     * "this person will reach you soon", which is a claim the platform cannot make about somebody
     * whose position it does not know.
     */
    @Test
    void aProfessionalWithNoUsableCurrentLocationIsExcludedFromSosEntirely() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250"), professional(2, 5.0, 10, "250")));
        when(distanceEtaStrategy.calculateBatch(any(), any(), any())).thenReturn(Map.of(
                1L, EtaResult.unavailable(RouteUnavailableReason.PROFESSIONAL_LOCATION_MISSING),
                2L, EtaResult.available(new BigDecimal("6.0"), 12, true)));

        SosMatchingService.MatchingOutcome outcome =
                service.findCandidates(request(SosUrgency.URGENT), Set.of());

        assertThat(outcome.candidates()).hasSize(1);
        assertThat(outcome.candidates().get(0).professional().professionalId()).isEqualTo(2L);
        assertThat(outcome.isDegraded()).isFalse();
    }

    @Test
    void aProfessionalWhoseLocationIsStaleIsExcludedFromSos() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250")));
        when(distanceEtaStrategy.calculateBatch(any(), any(), any())).thenReturn(Map.of(
                1L, EtaResult.unavailable(RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE)));

        SosMatchingService.MatchingOutcome outcome =
                service.findCandidates(request(SosUrgency.URGENT), Set.of());

        assertThat(outcome.candidates()).isEmpty();
        // Not degraded: the platform worked fine, this professional simply cannot be located.
        assertThat(outcome.isDegraded()).isFalse();
    }

    /**
     * <b>"We could not ask" is not "nobody qualifies".</b> Reporting a provider outage as an empty
     * candidate list would tell a customer with a burst pipe that no plumber is available, when
     * the truth is that Pronto could not measure how far away the available plumbers are.
     */
    @Test
    void aTotalProviderFailureIsReportedAsDegradedNotAsAnEmptyPool() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250"), professional(2, 5.0, 10, "250")));
        when(distanceEtaStrategy.calculateBatch(any(), any(), any())).thenReturn(Map.of(
                1L, EtaResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE),
                2L, EtaResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE)));

        SosMatchingService.MatchingOutcome outcome =
                service.findCandidates(request(SosUrgency.URGENT), Set.of());

        assertThat(outcome.candidates()).isEmpty();
        assertThat(outcome.isDegraded()).isTrue();
        assertThat(outcome.degradation())
                .isEqualTo(SosMatchingService.SosMatchingDegradation.ROUTING_UNAVAILABLE);
    }

    /**
     * A provider blip affecting one candidate among several is NOT a platform degradation -- the
     * others were evaluated fine. Escalating it would fail requests that had perfectly good
     * candidates.
     */
    @Test
    void oneCandidateFailingToRouteDoesNotDegradeTheWholeEvaluation() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250"), professional(2, 5.0, 10, "250")));
        when(distanceEtaStrategy.calculateBatch(any(), any(), any())).thenReturn(Map.of(
                1L, EtaResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE),
                2L, EtaResult.available(new BigDecimal("6.0"), 12, true)));

        SosMatchingService.MatchingOutcome outcome =
                service.findCandidates(request(SosUrgency.URGENT), Set.of());

        assertThat(outcome.isDegraded()).isFalse();
        assertThat(outcome.candidates()).hasSize(1);
    }

    @Test
    void aRequestWithNoResolvedDestinationIsDegradedNotEmpty() {
        SosRequest noDestination = new SosRequest(1L, 2L, CATEGORY_ID, null, "leak", SosUrgency.URGENT,
                "Tel Aviv", "Dizengoff", "10", null, null, null, null, null, null);
        setField(noDestination, "id", 100L);

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(noDestination, Set.of());

        assertThat(outcome.isDegraded()).isTrue();
        assertThat(outcome.degradation())
                .isEqualTo(SosMatchingService.SosMatchingDegradation.DESTINATION_UNKNOWN);
        // Nothing is even queried -- there is no point ranking people against a place we cannot
        // locate.
        Mockito.verify(sosCandidateRepository, Mockito.never()).findEligible(any(), any(), anyList());
    }

    // ---- service-area coverage (the Eilat bug, SOS side) ----
    //
    // SOS had the same defect as the standard listing: eligibility was category + live SOS
    // availability + approval + onboarding, with no geographic predicate. The radius filter
    // applied afterwards USUALLY hid it -- which is exactly why it went unnoticed -- but radius
    // is measured from a live device position that may be anywhere, and it degrades to
    // "unavailable" whenever the routing provider is down. Coverage is the rule; radius is a
    // range concern layered on top of it.

    @Test
    void theHardFilterIsAskedForTheRequestsResolvedServiceCity() {
        // The fix in one assertion: the canonical city id reaches the eligibility query, so a
        // professional who does not serve it is excluded in SQL rather than by distance luck.
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250")));

        service.findCandidates(request(SosUrgency.URGENT), Set.of());

        Mockito.verify(sosCandidateRepository).findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList());
    }

    @Test
    void aCityOutsideTheCatalogueIsDegraded_notReportedAsNobodyAvailable() {
        // The distinction matters more here than anywhere else on the platform. "Nobody is
        // available right now" invites a customer with an active leak to wait and retry; "we do
        // not operate where you are" tells them to call somebody else. Only the second is true
        // when the address named a place this platform has never heard of.
        Mockito.when(serviceCityResolver.resolveId(any())).thenReturn(java.util.Optional.empty());

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(request(SosUrgency.URGENT), Set.of());

        assertThat(outcome.isDegraded()).isTrue();
        assertThat(outcome.degradation())
                .isEqualTo(SosMatchingService.SosMatchingDegradation.SERVICE_AREA_UNCOVERED);
        assertThat(outcome.candidates()).isEmpty();
        // And nothing is queried: there is nobody whose coverage could include a city we cannot
        // name, so an unfiltered query would be the original bug.
        Mockito.verify(sosCandidateRepository, Mockito.never()).findEligible(any(), any(), anyList());
    }

    /**
     * Roadmap section 15. The pre-MS2 code called the strategy once per candidate inside the
     * ranking loop; against a real provider that is one HTTP round trip per professional per SOS
     * activation.
     */
    @Test
    void everyCandidateIsRoutedInOneBatchedCall() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(IntStream.rangeClosed(1, 15)
                        .mapToObj(i -> professional(i, 5.0, 10, "250"))
                        .toList());

        service.findCandidates(request(SosUrgency.URGENT), Set.of());

        Mockito.verify(distanceEtaStrategy, Mockito.times(1)).calculateBatch(any(), any(), any());
        Mockito.verify(distanceEtaStrategy, Mockito.never()).calculate(anyLong(), any(), any());
    }

    /**
     * The radius the existing expansion machinery has always multiplied is finally a real one.
     * Level 1 reaches 60 km with the default 1.5 multiplier, so a candidate 50 real kilometres
     * away is out of scope initially and in scope after one expansion.
     */
    @Test
    void expandingTheSearchGenuinelyReachesFurtherOutInRealKilometres() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), eq(SERVICE_CITY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250")));
        when(distanceEtaStrategy.calculateBatch(any(), any(), any()))
                .thenAnswer(inv -> uniformEta(inv.getArgument(0), new BigDecimal("50.0"), 55));

        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of()).candidates())
                .as("50 km is outside the default 40 km ceiling")
                .isEmpty();
        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of(),
                SosSearchScope.forLevel(1, SosUrgency.URGENT, properties)).candidates())
                .as("one expansion widens the ceiling to 60 km, which 50 km is inside")
                .hasSize(1);
    }
}
