package com.pronto.maps.fake;

import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.GeoDistance;
import com.pronto.maps.GeocodeResult;
import com.pronto.maps.PostalAddress;
import com.pronto.maps.RouteResult;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.maps.config.MapsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline providers the local environment and the automated suite run on.
 *
 * <p>What is being asserted here is not "the fake is accurate" — it is not, and says so. It is that
 * the fake is a genuine <b>function of real geometry</b>, because that is what makes every ordering,
 * radius and threshold test elsewhere in this suite meaningful. The pre-MS2 placeholder could only
 * ever return 8 or 35 km regardless of where anybody was; move a fixture 50 km and this moves with
 * it.
 */
class FakeMapsProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    private FakeGeocodingProvider geocoder;
    private FakeRoutingProvider router;

    @BeforeEach
    void setUp() {
        geocoder = new FakeGeocodingProvider();
        router = new FakeRoutingProvider(new MapsProperties());
    }

    // ---- geocoding ----

    @Test
    void anAddressInAKnownCityResolvesNearThatCity() {
        GeocodeResult result = geocoder.geocode(new PostalAddress("תל אביב", "דיזנגוף", "10"));

        assertThat(result.isResolved()).isTrue();
        // Within ~10 km of the real Tel Aviv centre -- a plausible intra-city point, not a random
        // one somewhere on Earth.
        double km = GeoDistance.kilometers(result.coordinates(), GeoCoordinates.of(32.0853, 34.7818));
        assertThat(km).isLessThan(10.0);
    }

    @Test
    void differentCitiesResolveToGenuinelyDifferentPlaces() {
        GeoCoordinates telAviv = geocoder.geocode(new PostalAddress("תל אביב", "הרצל", "1")).coordinates();
        GeoCoordinates beerSheva = geocoder.geocode(new PostalAddress("באר שבע", "הרצל", "1")).coordinates();

        // The real separation is ~85 km. If the fake collapsed cities, every radius test elsewhere
        // in this suite would be vacuous.
        assertThat(GeoDistance.kilometers(telAviv, beerSheva)).isBetween(70.0, 100.0);
    }

    /** Determinism is what makes fixtures stable across runs and machines. */
    @Test
    void theSameAddressAlwaysResolvesToTheSamePoint() {
        PostalAddress address = new PostalAddress("חיפה", "הנמל", "5");

        assertThat(geocoder.geocode(address).coordinates())
                .isEqualTo(new FakeGeocodingProvider().geocode(address).coordinates());
    }

    @Test
    void differentStreetsInOneCityResolveToDifferentPoints() {
        GeoCoordinates a = geocoder.geocode(new PostalAddress("תל אביב", "דיזנגוף", "10")).coordinates();
        GeoCoordinates b = geocoder.geocode(new PostalAddress("תל אביב", "אלנבי", "90")).coordinates();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void anAddressWithNoStreetIsRefusedRatherThanResolvedToACityCentroid() {
        assertThat(geocoder.geocode(new PostalAddress("תל אביב", null, null)).isResolved()).isFalse();
    }

    /** A reserved token, so the unresolvable-address path is testable without a provider outage. */
    @Test
    void theReservedFailureTokenProducesAnUnresolvableAddress() {
        GeocodeResult result = geocoder.geocode(new PostalAddress("תל אביב", "NO_SUCH_PLACE", "1"));

        assertThat(result.isResolved()).isFalse();
        assertThat(result.status()).isEqualTo(com.pronto.maps.GeocodeStatus.FAILED);
    }

    @Test
    void itDeclaresItselfFakeSoTheStartupGuardCanRefuseIt() {
        assertThat(geocoder.isFake()).isTrue();
        assertThat(router.isFake()).isTrue();
    }

    // ---- routing ----

    @Test
    void aLongerJourneyProducesBothAGreaterDistanceAndALongerDuration() {
        GeoCoordinates destination = GeoCoordinates.of(32.0853, 34.7818);
        RouteResult near = router.route(GeoCoordinates.of(32.1000, 34.8000), destination, NOW);
        RouteResult far = router.route(GeoCoordinates.of(32.7940, 34.9896), destination, NOW);

        assertThat(far.distanceMeters()).isGreaterThan(near.distanceMeters());
        assertThat(far.durationSeconds()).isGreaterThan(near.durationSeconds());
    }

    @Test
    void roadDistanceExceedsStraightLineDistance() {
        GeoCoordinates from = GeoCoordinates.of(32.1000, 34.8000);
        GeoCoordinates to = GeoCoordinates.of(32.0853, 34.7818);

        RouteResult route = router.route(from, to, NOW);

        assertThat(route.distanceMeters()).isGreaterThan((int) GeoDistance.meters(from, to));
    }

    /**
     * The honesty rule the real integration is also held to: a provider that cannot account for
     * traffic must not report a duration as though it could.
     */
    @Test
    void itNeverClaimsItsDurationsAreTrafficAware() {
        RouteResult route = router.route(GeoCoordinates.of(32.1, 34.8),
                GeoCoordinates.of(32.0853, 34.7818), NOW);

        assertThat(route.trafficAware()).isFalse();
        assertThat(router.supportsTrafficAwareDuration()).isFalse();
    }

    @Test
    void aMissingOriginOrDestinationIsAnUnavailableResultWithTheRightReason() {
        assertThat(router.route(null, GeoCoordinates.of(32.0, 34.0), NOW).unavailableReason())
                .isEqualTo(RouteUnavailableReason.PROFESSIONAL_LOCATION_MISSING);
        assertThat(router.route(GeoCoordinates.of(32.0, 34.0), null, NOW).unavailableReason())
                .isEqualTo(RouteUnavailableReason.DESTINATION_UNKNOWN);
    }

    @Test
    void theMatrixAnswersForEveryKeyItWasGiven() {
        Map<Long, GeoCoordinates> origins = new LinkedHashMap<>();
        origins.put(1L, GeoCoordinates.of(32.10, 34.80));
        origins.put(2L, GeoCoordinates.of(32.20, 34.90));
        origins.put(3L, GeoCoordinates.of(31.90, 34.70));

        Map<Long, RouteResult> results = router.routeMatrix(origins,
                GeoCoordinates.of(32.0853, 34.7818), NOW);

        assertThat(results).containsOnlyKeys(1L, 2L, 3L);
        assertThat(results.values()).allMatch(RouteResult::available);
    }

    /** Even a very short hop reports a plausible minimum, never "0 minutes". */
    @Test
    void aVeryShortJourneyStillCarriesRealisticOverhead() {
        GeoCoordinates from = GeoCoordinates.of(32.08530, 34.78180);
        GeoCoordinates to = GeoCoordinates.of(32.08540, 34.78190);

        assertThat(router.route(from, to, NOW).etaMinutes()).isGreaterThanOrEqualTo(1);
    }
}
