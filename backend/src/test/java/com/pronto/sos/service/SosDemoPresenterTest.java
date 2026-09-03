package com.pronto.sos.service;

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

import java.lang.reflect.Field;
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
 * ({@code professionals.demo_sos_presenter}) <em>and</em> the environment permits demo behaviour
 * ({@code pronto.environment} in local/demo/test). The most important tests in this class are the
 * negative ones: a presenter row that reaches Production must activate nothing at all.
 */
class SosDemoPresenterTest {

    private static final Long REQUEST_ID = 100L;
    private static final Long CATEGORY_ID = 7L;
    private static final Long SERVICE_CITY_ID = 4001L;
    private static final Long ORDINARY_PROFESSIONAL_ID = 3L;
    private static final Long PRESENTER_ID = 77L;

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

    /** The policy itself, stated directly: three names in, everything else out. */
    @Test
    void demoBehaviourIsAllowedOnlyInTheThreeNonProductionEnvironments() {
        assertThat(policy("local").isAllowed()).isTrue();
        assertThat(policy("demo").isAllowed()).isTrue();
        assertThat(policy("test").isAllowed()).isTrue();

        assertThat(policy("production").isAllowed()).isFalse();
        assertThat(policy("staging").isAllowed()).isFalse();
        assertThat(policy("prod-eu").isAllowed()).isFalse();
        assertThat(policy("PRODUCTION-2").isAllowed()).isFalse();
        assertThat(policy("").isAllowed()).isFalse();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private SosMatchingService matchingService(String environment) {
        return new SosMatchingService(sosCandidateRepository, sosOfferRepository, distanceEtaStrategy,
                serviceCityResolver, new SosProperties(), policy(environment));
    }

    private static DemoBehaviorPolicy policy(String environment) {
        return new DemoBehaviorPolicy(new ProntoEnvironment(environment));
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
        SosRequest request = new SosRequest(2L, 1L, CATEGORY_ID, null, "leak", SosUrgency.URGENT,
                "תל אביב-יפו", "דיזנגוף", "10", null, null, null, null,
                new BigDecimal("32.0811"), new BigDecimal("34.7739"));
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
