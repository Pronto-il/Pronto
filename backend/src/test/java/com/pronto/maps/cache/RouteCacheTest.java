package com.pronto.maps.cache;

import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.RouteResult;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.maps.config.MapsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-process route cache.
 *
 * <p>The two properties that matter are opposites of each other, and both are correctness rather
 * than performance concerns: a key must be coarse enough that a stationary device's GPS jitter
 * still hits, and fine enough that two genuinely different origins never share an entry — because
 * the consequence of the latter is attributing one professional's route to another.
 */
class RouteCacheTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final GeoCoordinates DESTINATION = GeoCoordinates.of(32.0853, 34.7818);
    private static final GeoCoordinates ORIGIN = GeoCoordinates.of(32.1000, 34.8000);

    private MapsProperties properties;
    private RouteCache cache;

    @BeforeEach
    void setUp() {
        properties = new MapsProperties();
        cache = new RouteCache(properties);
    }

    @Test
    void aStoredRouteIsReturnedForTheSameJourney() {
        cache.put(ORIGIN, DESTINATION, RouteResult.available(5000, 900, false), NOW);

        RouteResult hit = cache.get(ORIGIN, DESTINATION, NOW.plusSeconds(10));

        assertThat(hit).isNotNull();
        assertThat(hit.distanceMeters()).isEqualTo(5000);
    }

    @Test
    void anUnseenJourneyIsAMiss() {
        assertThat(cache.get(ORIGIN, DESTINATION, NOW)).isNull();
    }

    /** Direction matters: A-to-B and B-to-A are different journeys and must not share an entry. */
    @Test
    void reversingOriginAndDestinationIsADifferentJourney() {
        cache.put(ORIGIN, DESTINATION, RouteResult.available(5000, 900, false), NOW);

        assertThat(cache.get(DESTINATION, ORIGIN, NOW)).isNull();
    }

    @Test
    void aGenuinelyDifferentOriginNeverSharesAnEntry() {
        cache.put(ORIGIN, DESTINATION, RouteResult.available(5000, 900, false), NOW);

        // ~1 km away -- far beyond the key's quantisation.
        assertThat(cache.get(GeoCoordinates.of(32.1090, 34.8000), DESTINATION, NOW)).isNull();
    }

    /**
     * The other half: a device sitting still reports positions that wobble by a few metres. Without
     * quantisation the cache would miss on every single poll, which is most of the benefit gone.
     */
    @Test
    void aFewMetresOfGpsJitterStillHitsTheSameEntry() {
        cache.put(ORIGIN, DESTINATION, RouteResult.available(5000, 900, false), NOW);

        // ~0.5 m of jitter, well inside the ~11 m key resolution.
        GeoCoordinates jittered = GeoCoordinates.of(32.100004, 34.800002);
        assertThat(cache.get(jittered, DESTINATION, NOW)).isNotNull();
    }

    // ---- the two TTLs ----

    @Test
    void aNonTrafficAwareResultKeepsTheLongDistanceTtl() {
        cache.put(ORIGIN, DESTINATION, RouteResult.available(5000, 900, false), NOW);

        assertThat(cache.get(ORIGIN, DESTINATION,
                NOW.plusSeconds(properties.getTrafficDurationCacheTtlSeconds() + 60))).isNotNull();
        assertThat(cache.get(ORIGIN, DESTINATION,
                NOW.plusSeconds(properties.getDistanceCacheTtlSeconds() + 1))).isNull();
    }

    /**
     * A traffic-aware duration is a claim about right now. Serving one for a day would put back
     * exactly what MS2 removes: a confident number that stopped being true hours ago.
     */
    @Test
    void aTrafficAwareResultExpiresOnTheShortTtl() {
        cache.put(ORIGIN, DESTINATION, RouteResult.available(5000, 900, true), NOW);

        assertThat(cache.get(ORIGIN, DESTINATION,
                NOW.plusSeconds(properties.getTrafficDurationCacheTtlSeconds() - 1))).isNotNull();
        assertThat(cache.get(ORIGIN, DESTINATION,
                NOW.plusSeconds(properties.getTrafficDurationCacheTtlSeconds() + 1))).isNull();
    }

    @Test
    void aZeroTtlDisablesCachingRatherThanCachingForever() {
        properties.setDistanceCacheTtlSeconds(0);
        cache.put(ORIGIN, DESTINATION, RouteResult.available(5000, 900, false), NOW);

        assertThat(cache.get(ORIGIN, DESTINATION, NOW)).isNull();
    }

    /**
     * Caching an outage would extend it artificially past its end, and would suppress a whole
     * category of professionals for the rest of the TTL because of one bad second.
     */
    @Test
    void unavailableResultsAreNeverStored() {
        cache.put(ORIGIN, DESTINATION,
                RouteResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE), NOW);

        assertThat(cache.get(ORIGIN, DESTINATION, NOW)).isNull();
    }

    // ---- bounds and telemetry ----

    @Test
    void theCacheIsBoundedAndEvictsTheLeastRecentlyUsed() {
        properties.setRouteCacheMaxEntries(3);
        RouteCache bounded = new RouteCache(properties);
        RouteResult result = RouteResult.available(1000, 300, false);

        for (int i = 0; i < 10; i++) {
            bounded.put(GeoCoordinates.of(32.0 + i * 0.01, 34.8), DESTINATION, result, NOW);
        }

        // The first entries are gone; the last is still there.
        assertThat(bounded.get(GeoCoordinates.of(32.0, 34.8), DESTINATION, NOW)).isNull();
        assertThat(bounded.get(GeoCoordinates.of(32.09, 34.8), DESTINATION, NOW)).isNotNull();
    }

    @Test
    void hitAndMissCountsAreReportedForThePerformanceRecord() {
        cache.put(ORIGIN, DESTINATION, RouteResult.available(5000, 900, false), NOW);
        cache.get(ORIGIN, DESTINATION, NOW);
        cache.get(GeoCoordinates.of(31.0, 34.0), DESTINATION, NOW);

        assertThat(cache.statsSummary()).contains("hits=1").contains("misses=1");
    }

    @Test
    void aNullEndpointIsAMissRatherThanAnException() {
        assertThat(cache.get(null, DESTINATION, NOW)).isNull();
        assertThat(cache.get(ORIGIN, null, NOW)).isNull();
    }
}
