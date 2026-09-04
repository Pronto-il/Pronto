package com.pronto.sos.service;

import com.pronto.bookings.repository.ProfessionalListingRepository;
import com.pronto.common.config.ProntoEnvironment;
import com.pronto.demo.DemoBehaviorPolicy;
import com.pronto.locations.service.ServiceCityResolver;
import com.pronto.matching.DistanceEtaStrategy;
import com.pronto.matching.EtaResult;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.dto.EligibleProfessional;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.repository.SosCandidateRepository;
import com.pronto.sos.repository.SosOfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>The demo SOS presenter: one account that reliably receives SOS requests during a live
 * demonstration, and grants nothing anywhere else.</b>
 *
 * <p>The feature turns two keys at once — the professional is explicitly marked
 * ({@code professionals.demo_sos_presenter}) <em>and</em> demo behaviour is permitted here
 * ({@code pronto.environment} in local/demo/test, <em>or</em> an explicit
 * {@code pronto.demo.behavior-enabled=true}).
 *
 * <h2>Two groups of tests, pulling in opposite directions</h2>
 *
 * <ol>
 *   <li><b>The guarantee</b> — with the override on, the presenter is an eligible recipient of
 *       <em>every</em> SOS request: any region, any city (including one absent from the service
 *       catalogue), any category, any distance, no routable position, already busy, and even
 *       against a pool that is already full. Each of those was a separate way to miss a request,
 *       and each has a test here.</li>
 *   <li><b>The containment</b> — none of that moved a rule for anybody else. Real professionals
 *       still obey category, service city, location and radius; Regular (non-SOS) discovery never
 *       reads the demo flag at all; with no presenter row the degraded answers are unchanged; and
 *       with the property unset, Production still never runs the demo query.</li>
 * </ol>
 *
 * <p>The second group is the more important one, and deliberately outnumbers the first.
 */
class SosDemoPresenterTest {

    private static final Long REQUEST_ID = 100L;
    private static final Long CATEGORY_ID = 7L;
    private static final Long SERVICE_CITY_ID = 4001L;
    private static final Long ORDINARY_PROFESSIONAL_ID = 3L;
    private static final Long PRESENTER_ID = 77L;
    /** Mirrors {@code SosMatchingService.NO_EXCLUSIONS_SENTINEL} — JPQL cannot express {@code NOT IN ()}. */
    private static final Long NO_EXCLUSIONS_SENTINEL = -1L;

    private SosCandidateRepository sosCandidateRepository;
    private SosOfferRepository sosOfferRepository;
    private DistanceEtaStrategy distanceEtaStrategy;
    private ServiceCityResolver serviceCityResolver;

    @BeforeEach
    void setUp() {
        sosCandidateRepository = Mockito.mock(SosCandidateRepository.class);
        sosOfferRepository = Mockito.mock(SosOfferRepository.class);
        distanceEtaStrategy = Mockito.mock(DistanceEtaStrategy.class);
        serviceCityResolver = Mockito.mock(ServiceCityResolver.class);

        when(serviceCityResolver.resolveId(any())).thenReturn(Optional.of(SERVICE_CITY_ID));
        when(sosOfferRepository.findProfessionalIdsWithLiveOffers(anyList(), any())).thenReturn(List.of());
        when(sosOfferRepository.findAcceptanceStats(anyList(), any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // Demo environment: the presenter is asked
    // ------------------------------------------------------------------

    /**
     * The presenter is dispatched to even though the normal eligibility query returned nobody —
     * which is exactly the situation a demonstration runs in, where no real professional serves the
     * demo address in the demo category.
     */
    @Test
    void inADemoEnvironmentThePresenterReceivesTheRequestEvenWhenNobodyElseIsEligible() {
        SosMatchingService service = matchingService("demo");
        when(sosCandidateRepository.findEligible(any(), any(), anyList())).thenReturn(List.of());
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));
        when(distanceEtaStrategy.calculateBatch(anyList(), any(), any())).thenReturn(java.util.Map.of());

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(request(), Set.of());

        assertThat(outcome.candidates()).extracting(c -> c.professional().professionalId())
                .containsExactly(PRESENTER_ID);
    }

    /**
     * <b>No fresh device position required.</b> Production SOS excludes anybody it cannot route,
     * deliberately — but requiring a browser to have granted geolocation five minutes before a
     * presentation is precisely the manual pre-work this feature exists to remove.
     *
     * <p>The resulting candidate carries no distance and no platform estimate, which
     * {@code sos_offers} allows: they still have to state a real ETA when they accept.
     */
    @Test
    void thePresenterIsNotExcludedForHavingNoRoutablePosition() {
        SosMatchingService service = matchingService("local");
        when(sosCandidateRepository.findEligible(any(), any(), anyList())).thenReturn(List.of());
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));
        when(distanceEtaStrategy.calculateBatch(anyList(), any(), any())).thenReturn(java.util.Map.of(
                PRESENTER_ID, EtaResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE)));

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(request(), Set.of());

        assertThat(outcome.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.professional().professionalId()).isEqualTo(PRESENTER_ID);
            assertThat(candidate.distanceKm()).isNull();
            assertThat(candidate.etaMinutes()).isNull();
        });
    }

    /**
     * The presenter does not displace real professionals. Ranking still decides order, and the
     * presenter is scored mid-pack rather than pinned to the top — a demonstration that always put
     * the same person first would hide the ranking behaviour the rest of the demo dataset shows.
     */
    @Test
    void thePresenterJoinsTheRealCandidatesRatherThanReplacingThem() {
        SosMatchingService service = matchingService("demo");
        when(sosCandidateRepository.findEligible(any(), any(), anyList()))
                .thenReturn(List.of(ordinary()));
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));
        when(distanceEtaStrategy.calculateBatch(anyList(), any(), any())).thenReturn(java.util.Map.of(
                ORDINARY_PROFESSIONAL_ID, EtaResult.available(new BigDecimal("5.0"), 10, true)));

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(request(), Set.of());

        assertThat(outcome.candidates()).extracting(c -> c.professional().professionalId())
                .containsExactlyInAnyOrder(ORDINARY_PROFESSIONAL_ID, PRESENTER_ID);
    }

    /** Already holding an offer on this request, they are not sent a second one. */
    @Test
    void thePresenterIsNotContactedTwiceOnTheSameRequest() {
        SosMatchingService service = matchingService("demo");
        when(sosCandidateRepository.findEligible(any(), any(), anyList())).thenReturn(List.of());
        // The exclusion set is passed straight through to the demo query, exactly as it is to the
        // production one -- so an already-offered presenter simply does not come back.
        when(sosCandidateRepository.findDemoSosPresenters(List.of(PRESENTER_ID))).thenReturn(List.of());

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(request(), Set.of(PRESENTER_ID));

        assertThat(outcome.candidates()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Production: the flag is inert
    // ------------------------------------------------------------------

    /**
     * <b>The critical safety test.</b> A presenter row that exists in Production — restored from a
     * dump, created by a mistaken script — grants nothing, because the demo query is never even run.
     */
    @Test
    void inProductionThePresenterQueryIsNeverEvenRun() {
        SosMatchingService service = matchingService("production");
        when(sosCandidateRepository.findEligible(any(), any(), anyList())).thenReturn(List.of());

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(request(), Set.of());

        assertThat(outcome.candidates()).isEmpty();
        verify(sosCandidateRepository, never()).findDemoSosPresenters(anyList());
    }

    /**
     * Fail-safe on an unrecognised environment name. A typo in {@code PRONTO_ENVIRONMENT} switches
     * demo behaviour <em>off</em>, never on — the same direction every other guard in this codebase
     * fails.
     */
    @Test
    void anUnrecognisedEnvironmentIsTreatedAsProduction() {
        SosMatchingService service = matchingService("prod-eu");
        when(sosCandidateRepository.findEligible(any(), any(), anyList())).thenReturn(List.of());

        service.findCandidates(request(), Set.of());

        verify(sosCandidateRepository, never()).findDemoSosPresenters(anyList());
    }

    /**
     * The policy's <b>default</b>, with {@code pronto.demo.behavior-enabled} unset: three names in,
     * everything else out. Unchanged by the override existing — an unset property must keep the
     * historical answer verbatim, or every deployment that never heard of this key changes
     * behaviour on upgrade.
     */
    @Test
    void withNoOverrideDemoBehaviourIsAllowedOnlyInTheThreeNonProductionEnvironments() {
        assertThat(policy("local").isAllowed()).isTrue();
        assertThat(policy("demo").isAllowed()).isTrue();
        assertThat(policy("test").isAllowed()).isTrue();

        assertThat(policy("production").isAllowed()).isFalse();
        assertThat(policy("staging").isAllowed()).isFalse();
        assertThat(policy("prod-eu").isAllowed()).isFalse();
        assertThat(policy("PRODUCTION-2").isAllowed()).isFalse();
        assertThat(policy("").isAllowed()).isFalse();
    }

    /** The override wins in both directions, and only when it is actually set. */
    @Test
    void anExplicitOverrideDecidesRegardlessOfEnvironment() {
        assertThat(policy("production", true).isAllowed()).isTrue();
        assertThat(policy("staging", true).isAllowed()).isTrue();

        // ...and can switch demo behaviour OFF where the environment would have allowed it.
        assertThat(policy("local", false).isAllowed()).isFalse();
        assertThat(policy("demo", false).isAllowed()).isFalse();
    }

    // ------------------------------------------------------------------
    // Production WITH the override: the presenter receives every SOS request
    // ------------------------------------------------------------------

    /**
     * <b>Requirement 1 — any region.</b> The demo query takes no region, city or category argument
     * at all, so there is no region for a presenter to fall outside of. Asserted over several
     * genuinely different regions rather than by inspecting the signature, so the guarantee is
     * proven at the level a demonstration actually experiences it.
     */
    @Test
    void inProductionWithTheOverrideAnSosInAnyRegionIncludesThePresenter() {
        SosMatchingService service = matchingService("production", true);
        when(sosCandidateRepository.findEligible(any(), any(), anyList())).thenReturn(List.of());
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));
        when(distanceEtaStrategy.calculateBatch(anyList(), any(), any())).thenReturn(java.util.Map.of());

        for (String[] place : new String[][] {
                {"תל אביב-יפו", "גוש דן"},
                {"חיפה", "חיפה והצפון"},
                {"אילת", "הדרום"},
                {"קצרין", "הגולן"}}) {
            SosMatchingService.MatchingOutcome outcome =
                    service.findCandidates(requestIn(place[0]), Set.of());

            assertThat(outcome.candidates())
                    .as("SOS in %s (%s) must reach the presenter", place[0], place[1])
                    .extracting(c -> c.professional().professionalId())
                    .contains(PRESENTER_ID);
        }
    }

    /**
     * <b>Requirement 2 — a category the presenter does not serve.</b> The ordinary query returns
     * nobody for this category (which is what "the presenter does not serve it" looks like from
     * here), and the presenter is dispatched anyway.
     *
     * <p>Also pins the mechanism: the demo query is called with the exclusion list and nothing
     * else. If a category argument were ever added to it, this stops compiling — which is the
     * point, because a category-aware demo query could silently reintroduce the filter.
     */
    @Test
    void inProductionWithTheOverrideAnUnservedCategoryStillIncludesThePresenter() {
        SosMatchingService service = matchingService("production", true);
        when(sosCandidateRepository.findEligible(any(), any(), anyList())).thenReturn(List.of());
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));
        when(distanceEtaStrategy.calculateBatch(anyList(), any(), any())).thenReturn(java.util.Map.of());

        for (long categoryId : new long[] {1L, 2L, 3L, 99L}) {
            SosMatchingService.MatchingOutcome outcome =
                    service.findCandidates(requestFor(categoryId), Set.of());

            assertThat(outcome.candidates())
                    .as("SOS in category %s must reach the presenter", categoryId)
                    .extracting(c -> c.professional().professionalId())
                    .containsExactly(PRESENTER_ID);
        }
        verify(sosCandidateRepository, Mockito.atLeastOnce())
                .findDemoSosPresenters(List.of(NO_EXCLUSIONS_SENTINEL));
    }

    /**
     * <b>Requirement 3 — any city, including one the catalogue has never heard of.</b>
     *
     * <p>This is the path that used to return {@code SERVICE_AREA_UNCOVERED} before the presenter
     * was ever looked up. An uncovered city is exactly what a demonstration against an arbitrary
     * address produces, so "all cities" is untrue without it.
     */
    @Test
    void inProductionWithTheOverrideACityOutsideTheCatalogueStillIncludesThePresenter() {
        SosMatchingService service = matchingService("production", true);
        when(serviceCityResolver.resolveId(any())).thenReturn(Optional.empty());
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));

        SosMatchingService.MatchingOutcome outcome =
                service.findCandidates(requestIn("כפר שאיננו בקטלוג"), Set.of());

        assertThat(outcome.isDegraded()).isFalse();
        assertThat(outcome.candidates()).extracting(c -> c.professional().professionalId())
                .containsExactly(PRESENTER_ID);
    }

    /** An address that never geocoded — the other pre-matching return — also reaches the presenter. */
    @Test
    void inProductionWithTheOverrideAnUngeocodableAddressStillIncludesThePresenter() {
        SosMatchingService service = matchingService("production", true);
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));

        SosMatchingService.MatchingOutcome outcome =
                service.findCandidates(requestWithoutCoordinates(), Set.of());

        assertThat(outcome.isDegraded()).isFalse();
        assertThat(outcome.candidates()).extracting(c -> c.professional().professionalId())
                .containsExactly(PRESENTER_ID);
    }

    /** Holding live offers elsewhere no longer skips the presenter. */
    @Test
    void aBusyPresenterIsStillDispatched() {
        SosMatchingService service = matchingService("production", true);
        when(sosCandidateRepository.findEligible(any(), any(), anyList())).thenReturn(List.of(ordinary()));
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));
        // Both are busy on other requests; only the presenter is exempt from the rule.
        when(sosOfferRepository.findProfessionalIdsWithLiveOffers(anyList(), any()))
                .thenReturn(List.of(PRESENTER_ID));
        when(distanceEtaStrategy.calculateBatch(anyList(), any(), any())).thenReturn(java.util.Map.of(
                ORDINARY_PROFESSIONAL_ID, EtaResult.available(new BigDecimal("5.0"), 10, true)));

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(request(), Set.of());

        assertThat(outcome.candidates()).extracting(c -> c.professional().professionalId())
                .contains(PRESENTER_ID);
    }

    /**
     * <b>The busy-fallback still fires for real professionals when a presenter is present.</b>
     *
     * <p>A regression this change nearly introduced, and the reason the busy rule is now evaluated
     * over real professionals separately. The fallback's condition is "excluding busy candidates
     * left nobody"; a presenter who is exempt from the rule always survives it, so folding them
     * into the same list makes that condition unreachable and a fully-busy pool of real
     * professionals gets silently replaced by the presenter alone.
     *
     * <p>Both real professionals here are busy. Both must still be dispatched, alongside the
     * presenter — exactly as they would be with no presenter in the picture.
     */
    @Test
    void whenEveryRealProfessionalIsBusyTheyAreStillTakenBack_notReplacedByThePresenter() {
        SosMatchingService service = matchingService("production", true);
        EligibleProfessional second = new EligibleProfessional(504L, 5040L, "עסוק גם הוא",
                "תל אביב-יפו", "גוש דן", new BigDecimal("250.00"), new BigDecimal("0.90"), null, 5.0, 9L);
        when(sosCandidateRepository.findEligible(any(), any(), anyList()))
                .thenReturn(List.of(ordinary(), second));
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));
        when(sosOfferRepository.findProfessionalIdsWithLiveOffers(anyList(), any()))
                .thenReturn(List.of(ORDINARY_PROFESSIONAL_ID, 504L));
        when(distanceEtaStrategy.calculateBatch(anyList(), any(), any())).thenReturn(java.util.Map.of(
                ORDINARY_PROFESSIONAL_ID, EtaResult.available(new BigDecimal("5.0"), 10, true),
                504L, EtaResult.available(new BigDecimal("6.0"), 12, true)));

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(request(), Set.of());

        assertThat(outcome.candidates()).extracting(c -> c.professional().professionalId())
                .containsExactlyInAnyOrder(ORDINARY_PROFESSIONAL_ID, 504L, PRESENTER_ID);
    }

    /**
     * The pool cap truncates real professionals, never the presenter. With a default pool of 8 and
     * twelve strong real candidates, the mid-pack presenter used to fall off the end of the list.
     */
    @Test
    void thePresenterIsNotTruncatedByThePoolCap() {
        SosMatchingService service = matchingService("production", true);
        List<EligibleProfessional> crowd = new java.util.ArrayList<>();
        java.util.Map<Long, EtaResult> etas = new java.util.HashMap<>();
        for (long id = 200L; id < 212L; id++) {
            crowd.add(new EligibleProfessional(id, id + 1000L, "דוד " + id, "תל אביב-יפו", "גוש דן",
                    new BigDecimal("250.00"), new BigDecimal("0.99"), null, 5.0, 50L));
            // Excellent on every component, so all twelve outrank a mid-pack presenter.
            etas.put(id, EtaResult.available(new BigDecimal("1.0"), 2, true));
        }
        when(sosCandidateRepository.findEligible(any(), any(), anyList())).thenReturn(crowd);
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));
        when(distanceEtaStrategy.calculateBatch(anyList(), any(), any())).thenReturn(etas);

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(request(), Set.of());

        assertThat(outcome.candidates()).extracting(c -> c.professional().professionalId())
                .contains(PRESENTER_ID);
        // Real professionals keep every slot they had: the presenter is additive, not a
        // replacement for the last-ranked real candidate.
        assertThat(outcome.candidates()).hasSize(new SosProperties().getCandidatePoolSize() + 1);
    }

    /** A pool already full of real professionals still yields one more offer — the presenter's. */
    @Test
    void aFullPoolStillDispatchesThePresenter() {
        SosMatchingService service = matchingService("production", true);
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));
        Set<Long> alreadyOffered = new java.util.HashSet<>();
        for (long id = 300L; id < 308L; id++) {
            alreadyOffered.add(id);
        }

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(request(), alreadyOffered);

        assertThat(outcome.candidates()).extracting(c -> c.professional().professionalId())
                .containsExactly(PRESENTER_ID);
    }

    // ------------------------------------------------------------------
    // Requirement 4: nothing above moved a rule for a real professional
    // ------------------------------------------------------------------

    /**
     * <b>Requirement 4.</b> With demo behaviour fully on, real professionals are still filtered by
     * category and service city (the query is called with exactly those two, unchanged), and are
     * still excluded for having no routable position or for being outside the radius. Only the
     * presenter survives those rules.
     */
    @Test
    void realProfessionalsStillObeyCategoryCityLocationAndRadiusRules() {
        SosMatchingService service = matchingService("production", true);
        EligibleProfessional unroutable = new EligibleProfessional(501L, 5010L, "לא ניתן לניתוב",
                "תל אביב-יפו", "גוש דן", new BigDecimal("250.00"), new BigDecimal("0.90"), null, 5.0, 9L);
        EligibleProfessional tooFar = new EligibleProfessional(502L, 5020L, "רחוק מדי",
                "תל אביב-יפו", "גוש דן", new BigDecimal("250.00"), new BigDecimal("0.90"), null, 5.0, 9L);
        when(sosCandidateRepository.findEligible(any(), any(), anyList()))
                .thenReturn(List.of(unroutable, tooFar));
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of(presenter()));
        when(distanceEtaStrategy.calculateBatch(anyList(), any(), any())).thenReturn(java.util.Map.of(
                501L, EtaResult.unavailable(RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE),
                // Far outside the 40 km default dispatch radius.
                502L, EtaResult.available(new BigDecimal("450.0"), 300, true)));

        SosMatchingService.MatchingOutcome outcome = service.findCandidates(request(), Set.of());

        // The presenter is exempt from both rules; the two real professionals are not.
        assertThat(outcome.candidates()).extracting(c -> c.professional().professionalId())
                .containsExactly(PRESENTER_ID);
        // And the production eligibility query still received the real category and service city:
        // the demo path did not widen, replace or bypass it.
        verify(sosCandidateRepository).findEligible(CATEGORY_ID, SERVICE_CITY_ID,
                List.of(NO_EXCLUSIONS_SENTINEL));
    }

    /**
     * The other half of requirement 4, and the most important regression guard in this class: with
     * <em>no presenter row</em>, every degraded answer is byte-for-byte what it was. Turning the
     * property on changes nothing for a deployment that never marked anybody.
     */
    @Test
    void withNoPresenterRowTheDegradedAnswersAreUnchanged() {
        SosMatchingService service = matchingService("production", true);
        when(sosCandidateRepository.findDemoSosPresenters(anyList())).thenReturn(List.of());
        when(sosCandidateRepository.findEligible(any(), any(), anyList())).thenReturn(List.of());

        when(serviceCityResolver.resolveId(any())).thenReturn(Optional.empty());
        assertThat(service.findCandidates(request(), Set.of()).degradation())
                .isEqualTo(SosMatchingService.SosMatchingDegradation.SERVICE_AREA_UNCOVERED);

        when(serviceCityResolver.resolveId(any())).thenReturn(Optional.of(SERVICE_CITY_ID));
        assertThat(service.findCandidates(requestWithoutCoordinates(), Set.of()).degradation())
                .isEqualTo(SosMatchingService.SosMatchingDegradation.DESTINATION_UNKNOWN);
    }

    /**
     * <b>Requirement 5 — Regular (non-SOS) discovery is unaffected.</b> Structural, in this
     * repository's established idiom ({@code ProfessionalEligibilityTest}): the browse-and-book
     * listing queries are a different repository, and no demo concept appears in any of them. A
     * presenter shows up in a Standard listing only for the trades and cities they genuinely
     * declared, exactly like anybody else.
     */
    @Test
    void regularNonSosDiscoveryNeverConsultsTheDemoFlag() {
        for (Method method : ProfessionalListingRepository.class.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query == null) {
                continue;
            }
            assertThat(query.value())
                    .as("Regular listing query %s must not read any demo concept", method.getName())
                    .doesNotContain("demoSosPresenter")
                    .doesNotContain("demo_sos_presenter");
        }
        // And the demo relaxation is reachable from exactly one query, on the SOS side.
        assertThat(SosCandidateRepository.class.getDeclaredMethods())
                .filteredOn(m -> m.getAnnotation(Query.class) != null
                        && m.getAnnotation(Query.class).value().contains("demoSosPresenter"))
                .extracting(Method::getName)
                .containsExactly("findDemoSosPresenters");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private SosMatchingService matchingService(String environment) {
        return matchingService(environment, null);
    }

    /** @param override the {@code pronto.demo.behavior-enabled} value; {@code null} means unset. */
    private SosMatchingService matchingService(String environment, Boolean override) {
        return new SosMatchingService(sosCandidateRepository, sosOfferRepository, distanceEtaStrategy,
                serviceCityResolver, new SosProperties(), policy(environment, override));
    }

    private static DemoBehaviorPolicy policy(String environment) {
        return policy(environment, null);
    }

    private static DemoBehaviorPolicy policy(String environment, Boolean override) {
        return new DemoBehaviorPolicy(new ProntoEnvironment(environment), override);
    }

    private static EligibleProfessional presenter() {
        return new EligibleProfessional(PRESENTER_ID, 777L, "יונתן אבידן", "תל אביב-יפו", "גוש דן",
                new BigDecimal("250.00"), new BigDecimal("0.90"), null, null, 0L);
    }

    private static EligibleProfessional ordinary() {
        return new EligibleProfessional(ORDINARY_PROFESSIONAL_ID, 33L, "דוד כהן", "תל אביב-יפו", "גוש דן",
                new BigDecimal("250.00"), new BigDecimal("0.90"), null, null, 0L);
    }

    private static SosRequest request() {
        return request(CATEGORY_ID, "תל אביב-יפו", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
    }

    /** Same request, somewhere else entirely — for the "any region / any city" guarantees. */
    private static SosRequest requestIn(String city) {
        return request(CATEGORY_ID, city, new BigDecimal("32.0811"), new BigDecimal("34.7739"));
    }

    /** Same request, in a trade the presenter has not declared. */
    private static SosRequest requestFor(Long categoryId) {
        return request(categoryId, "תל אביב-יפו", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
    }

    /** An address that never geocoded, so there is no destination to measure anybody against. */
    private static SosRequest requestWithoutCoordinates() {
        return request(CATEGORY_ID, "תל אביב-יפו", null, null);
    }

    private static SosRequest request(Long categoryId, String city, BigDecimal latitude,
                                       BigDecimal longitude) {
        SosRequest request = new SosRequest(2L, 1L, categoryId, null, "leak", SosUrgency.URGENT,
                city, "דיזנגוף", "10", null, null, null, null, latitude, longitude);
        setField(request, "id", REQUEST_ID);
        return request;
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
