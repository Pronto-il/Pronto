package com.pronto.matching;

import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.MapsProviderException;
import com.pronto.maps.RouteResult;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.maps.RoutingProvider;
import com.pronto.maps.cache.RouteCache;
import com.pronto.maps.config.MapsProperties;
import com.pronto.professionals.service.ProfessionalLocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The production distance/ETA strategy — and above all, the guarantee that it never invents a
 * figure.
 *
 * <p>Every test below that ends in an unavailable result is really testing the same rule from a
 * different angle: <b>there is no input, no failure and no configuration under which this class
 * produces a distance or an ETA it did not get from a real route.</b> That rule is what the whole
 * milestone is for, and the pre-MS2 implementation violated it on every single call.
 */
class RoutedDistanceEtaStrategyTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final GeoCoordinates DESTINATION = GeoCoordinates.of(32.0853, 34.7818);
    private static final GeoCoordinates ORIGIN_A = GeoCoordinates.of(32.1000, 34.8000);
    private static final GeoCoordinates ORIGIN_B = GeoCoordinates.of(32.2000, 34.9000);

    private RoutingProvider routingProvider;
    private ProfessionalLocationService locationService;
    private RouteCache routeCache;
    private MapsProperties properties;
    private RoutedDistanceEtaStrategy strategy;

    @BeforeEach
    void setUp() {
        routingProvider = Mockito.mock(RoutingProvider.class);
        locationService = Mockito.mock(ProfessionalLocationService.class);
        properties = new MapsProperties();
        routeCache = new RouteCache(properties);
        strategy = new RoutedDistanceEtaStrategy(routingProvider, locationService, routeCache, properties);
    }

    private void origins(Map<Long, GeoCoordinates> usable, Map<Long, RouteUnavailableReason> rejected) {
        when(locationService.lookup(any(), any()))
                .thenReturn(new ProfessionalLocationService.LocationLookup(usable, rejected));
    }

    private static Map<Long, GeoCoordinates> map(Object... pairs) {
        Map<Long, GeoCoordinates> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((Long) pairs[i], (GeoCoordinates) pairs[i + 1]);
        }
        return result;
    }

    // ---- the happy path ----

    @Test
    void aRoutableProfessionalGetsTheProvidersOwnDistanceAndDuration() {
        origins(map(1L, ORIGIN_A), Map.of());
        when(routingProvider.routeMatrix(any(), any(), any()))
                .thenReturn(Map.of(1L, RouteResult.available(7350, 1080, true)));

        EtaResult eta = strategy.calculate(1L, DESTINATION, NOW);

        assertThat(eta.available()).isTrue();
        assertThat(eta.distanceKm()).isEqualByComparingTo("7.4");
        assertThat(eta.etaMinutes()).isEqualTo(18);
        assertThat(eta.trafficAware()).isTrue();
    }

    /**
     * The provider's traffic-awareness is carried through, never assumed. A platform that labelled
     * a plain duration as traffic-aware would be claiming precision it did not buy.
     */
    @Test
    void trafficAwarenessIsReportedAsTheProviderReportedIt() {
        origins(map(1L, ORIGIN_A), Map.of());
        when(routingProvider.routeMatrix(any(), any(), any()))
                .thenReturn(Map.of(1L, RouteResult.available(7350, 1080, false)));

        assertThat(strategy.calculate(1L, DESTINATION, NOW).trafficAware()).isFalse();
    }

    // ---- gate 1: destination ----

    @Test
    void withNoGeocodedDestinationNothingIsRoutedAndEveryResultSaysWhy() {
        EtaResult eta = strategy.calculate(1L, null, NOW);

        assertThat(eta.available()).isFalse();
        assertThat(eta.unavailableReason()).isEqualTo(RouteUnavailableReason.DESTINATION_UNKNOWN);
        // Short-circuits before touching the database or the provider.
        verify(locationService, never()).lookup(any(), any());
        verify(routingProvider, never()).routeMatrix(any(), any(), any());
    }

    // ---- gate 2: origin ----

    @Test
    void aProfessionalWithNoUsablePositionIsNeverRoutedAndNeverApproximated() {
        origins(Map.of(), Map.of(1L, RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE));

        EtaResult eta = strategy.calculate(1L, DESTINATION, NOW);

        assertThat(eta.available()).isFalse();
        assertThat(eta.distanceKm()).isNull();
        assertThat(eta.etaMinutes()).isNull();
        assertThat(eta.unavailableReason()).isEqualTo(RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE);
        verify(routingProvider, never()).routeMatrix(any(), any(), any());
    }

    /**
     * The specific defect MS2 exists to remove, asserted directly: no code path produces the old
     * placeholder figures. There is no fallback to fall back to.
     */
    @Test
    void noFailurePathEverProducesTheOldPlaceholderFiguresOrAnyOtherNumber() {
        origins(Map.of(),
                Map.of(1L, RouteUnavailableReason.PROFESSIONAL_LOCATION_MISSING,
                        2L, RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE,
                        3L, RouteUnavailableReason.PROFESSIONAL_LOCATION_INACCURATE));

        Map<Long, EtaResult> results = strategy.calculateBatch(List.of(1L, 2L, 3L), DESTINATION, NOW);

        assertThat(results).hasSize(3);
        assertThat(results.values()).allSatisfy(eta -> {
            assertThat(eta.available()).isFalse();
            assertThat(eta.distanceKm()).isNull();
            assertThat(eta.etaMinutes()).isNull();
        });
    }

    // ---- gate 4: provider failure ----

    @Test
    void aProviderOutageProducesUnavailableResults_notEstimates() {
        origins(map(1L, ORIGIN_A, 2L, ORIGIN_B), Map.of());
        when(routingProvider.routeMatrix(any(), any(), any())).thenReturn(Map.of(
                1L, RouteResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE),
                2L, RouteResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE)));

        Map<Long, EtaResult> results = strategy.calculateBatch(List.of(1L, 2L), DESTINATION, NOW);

        assertThat(results.values()).allSatisfy(eta -> {
            assertThat(eta.available()).isFalse();
            assertThat(eta.unavailableReason()).isEqualTo(RouteUnavailableReason.PROVIDER_UNAVAILABLE);
        });
    }

    /**
     * A misconfigured API key is an operator problem, not a customer-facing 500. The listing
     * degrades to "no ETA" and stays usable.
     */
    @Test
    void aProviderConfigurationFaultDegradesTheListingRatherThanFailingTheRequest() {
        origins(map(1L, ORIGIN_A), Map.of());
        when(routingProvider.routeMatrix(any(), any(), any()))
                .thenThrow(new MapsProviderException("google", "bad key"));

        Map<Long, EtaResult> results = strategy.calculateBatch(List.of(1L), DESTINATION, NOW);

        assertThat(results.get(1L).available()).isFalse();
        assertThat(results.get(1L).unavailableReason()).isEqualTo(RouteUnavailableReason.PROVIDER_UNAVAILABLE);
    }

    /**
     * A provider that silently omits an element must not leave a candidate unaccounted for — the
     * seeded-unavailable pass in the provider plus this default here are two layers of the same
     * guarantee.
     */
    @Test
    void anIdTheProviderDidNotAnswerForStillGetsAnExplicitUnavailableResult() {
        origins(map(1L, ORIGIN_A, 2L, ORIGIN_B), Map.of());
        when(routingProvider.routeMatrix(any(), any(), any()))
                .thenReturn(Map.of(1L, RouteResult.available(1000, 300, true)));

        Map<Long, EtaResult> results = strategy.calculateBatch(List.of(1L, 2L), DESTINATION, NOW);

        assertThat(results).containsOnlyKeys(1L, 2L);
        assertThat(results.get(2L).available()).isFalse();
    }

    // ---- N+1 avoidance and the routing budget ----

    @Test
    void manyCandidatesCostOneProviderCallNotOnePerCandidate() {
        Map<Long, GeoCoordinates> usable = new LinkedHashMap<>();
        for (long id = 1; id <= 30; id++) {
            usable.put(id, GeoCoordinates.of(32.0 + id / 1000.0, 34.8));
        }
        origins(usable, Map.of());
        when(routingProvider.routeMatrix(any(), any(), any())).thenReturn(Map.of());

        strategy.calculateBatch(usable.keySet(), DESTINATION, NOW);

        // One call into the provider abstraction; chunking into provider-sized batches is the
        // provider's own concern (see GoogleRoutingProvider), not thirty calls from here.
        verify(routingProvider, times(1)).routeMatrix(any(), any(), any());
    }

    /**
     * The hard stop on provider-call explosion. Overflow candidates are reported unavailable
     * rather than silently dropped — a missing entry would read downstream as "this person does
     * not exist", which is a different and worse lie than "we have no ETA for them".
     */
    @Test
    void candidatesBeyondTheRoutingBudgetAreReportedUnavailableRatherThanRouted() {
        properties.setMaxRoutedCandidates(2);
        Map<Long, GeoCoordinates> usable = map(1L, ORIGIN_A, 2L, ORIGIN_B, 3L, GeoCoordinates.of(32.3, 35.0));
        origins(usable, Map.of());
        when(routingProvider.routeMatrix(any(), any(), any())).thenAnswer(inv -> {
            Map<Long, GeoCoordinates> sent = inv.getArgument(0);
            assertThat(sent).hasSize(2);
            Map<Long, RouteResult> answer = new LinkedHashMap<>();
            sent.keySet().forEach(id -> answer.put(id, RouteResult.available(1000, 600, true)));
            return answer;
        });

        Map<Long, EtaResult> results = strategy.calculateBatch(List.of(1L, 2L, 3L), DESTINATION, NOW);

        assertThat(results).hasSize(3);
        assertThat(results.get(1L).available()).isTrue();
        assertThat(results.get(2L).available()).isTrue();
        assertThat(results.get(3L).available()).isFalse();
    }

    // ---- caching ----

    @Test
    void asecondEvaluationOfTheSameJourneyCostsNoProviderCall() {
        origins(map(1L, ORIGIN_A), Map.of());
        when(routingProvider.routeMatrix(any(), any(), any()))
                .thenReturn(Map.of(1L, RouteResult.available(5000, 900, false)));

        strategy.calculate(1L, DESTINATION, NOW);
        EtaResult second = strategy.calculate(1L, DESTINATION, NOW.plusSeconds(30));

        verify(routingProvider, times(1)).routeMatrix(any(), any(), any());
        assertThat(second.distanceKm()).isEqualByComparingTo("5.0");
    }

    /**
     * A traffic-aware duration gets the short TTL. Serving one for hours would reintroduce
     * precisely what MS2 removes: a confident number that stopped being true.
     */
    @Test
    void aTrafficAwareResultIsReRoutedOnceItsShortTtlElapses() {
        origins(map(1L, ORIGIN_A), Map.of());
        when(routingProvider.routeMatrix(any(), any(), any()))
                .thenReturn(Map.of(1L, RouteResult.available(5000, 900, true)));

        strategy.calculate(1L, DESTINATION, NOW);
        strategy.calculate(1L, DESTINATION,
                NOW.plusSeconds(properties.getTrafficDurationCacheTtlSeconds() + 1));

        verify(routingProvider, times(2)).routeMatrix(any(), any(), any());
    }

    @Test
    void anUnavailableResultIsNeverCached() {
        origins(map(1L, ORIGIN_A), Map.of());
        when(routingProvider.routeMatrix(any(), any(), any()))
                .thenReturn(Map.of(1L, RouteResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE)))
                .thenReturn(Map.of(1L, RouteResult.available(5000, 900, true)));

        strategy.calculate(1L, DESTINATION, NOW);
        EtaResult second = strategy.calculate(1L, DESTINATION, NOW.plusSeconds(1));

        // Caching the outage would have extended it artificially past its end.
        verify(routingProvider, times(2)).routeMatrix(any(), any(), any());
        assertThat(second.available()).isTrue();
    }

    // ---- contract details ----

    @Test
    void everyRequestedIdAppearsExactlyOnceInTheResult() {
        origins(map(1L, ORIGIN_A), Map.of(2L, RouteUnavailableReason.PROFESSIONAL_LOCATION_MISSING));
        when(routingProvider.routeMatrix(any(), any(), any()))
                .thenReturn(Map.of(1L, RouteResult.available(1000, 300, true)));

        assertThat(strategy.calculateBatch(List.of(1L, 2L), DESTINATION, NOW)).containsOnlyKeys(1L, 2L);
    }

    @Test
    void aDuplicatedIdIsNotRoutedTwice() {
        origins(map(1L, ORIGIN_A), Map.of());
        when(routingProvider.routeMatrix(any(), any(), any())).thenAnswer(inv -> {
            assertThat((Map<?, ?>) inv.getArgument(0)).hasSize(1);
            return Map.of(1L, RouteResult.available(1000, 300, true));
        });

        assertThat(strategy.calculateBatch(List.of(1L, 1L, 1L), DESTINATION, NOW)).hasSize(1);
    }

    @Test
    void anEmptyCandidateListDoesNothingAtAll() {
        assertThat(strategy.calculateBatch(List.of(), DESTINATION, NOW)).isEmpty();
        verify(locationService, never()).lookup(any(), any());
        verify(routingProvider, never()).routeMatrix(any(), any(), any());
    }

    /** Sub-minute journeys read as "1 minute", never "0" — a duration nobody can achieve. */
    @Test
    void aVeryShortJourneyRoundsUpToOneMinute() {
        origins(map(1L, ORIGIN_A), Map.of());
        when(routingProvider.routeMatrix(any(), any(), any()))
                .thenReturn(Map.of(1L, RouteResult.available(120, 25, true)));

        assertThat(strategy.calculate(1L, DESTINATION, NOW).etaMinutes()).isEqualTo(1);
    }

    @Test
    void distanceIsReportedToOneDecimalKilometre() {
        origins(map(1L, ORIGIN_A), Map.of());
        when(routingProvider.routeMatrix(any(), any(), any()))
                .thenReturn(Map.of(1L, RouteResult.available(12_349, 1500, true)));

        assertThat(strategy.calculate(1L, DESTINATION, NOW).distanceKm())
                .isEqualByComparingTo(new BigDecimal("12.3"));
    }
}
