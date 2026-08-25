package com.pronto.maps.google;

import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.GeoDistance;
import com.pronto.maps.GeocodeResult;
import com.pronto.maps.PostalAddress;
import com.pronto.maps.RouteResult;
import com.pronto.maps.config.MapsClientConfig;
import com.pronto.maps.config.MapsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Live verification against the real Google Maps Platform.</b> Opt-in, never part of the
 * ordinary suite.
 *
 * <h2>How to run it</h2>
 *
 * <pre>
 *   MAPS_LIVE_TEST=true MAPS_API_KEY=&lt;a key with Geocoding API + Routes API enabled&gt; \
 *     mvn test -Dtest=GoogleMapsLiveApiTest
 * </pre>
 *
 * <h2>Why it is separate, and why it is not deleted</h2>
 *
 * The contract tests ({@code GoogleMapsProviderContractTest}) prove that this codebase builds the
 * right requests and correctly interprets Google's documented response shapes, against a local
 * stub. What they cannot prove is that those shapes are still what Google actually sends, that the
 * deployment's key is valid and has both APIs enabled, or that Israeli Hebrew addresses resolve
 * well in practice. Only a real call answers those, and they are exactly the questions that matter
 * before a launch.
 *
 * <p>So it lives here, disabled by default ({@code @EnabledIfEnvironmentVariable}), tagged
 * {@code live}, and costs real quota when run. Roadmap §36: the normal suite must not depend on
 * internet access; §37: live validation must nevertheless be performed and recorded.
 *
 * <p><b>These assertions are deliberately loose about exact figures.</b> Road networks change and
 * traffic varies; asserting "Tel Aviv to Haifa is 94.2 km" would be a test that fails when reality
 * changes rather than when this code breaks. What is asserted is what must always hold: real
 * addresses resolve near where they are, an intercity route is much longer than an intra-city one,
 * and a nonsense address does not quietly resolve to somewhere plausible.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "MAPS_LIVE_TEST", matches = "true")
class GoogleMapsLiveApiTest {

    /** Kikar Rabin, Tel Aviv — a real, unambiguous, well-known address. */
    private static final PostalAddress TEL_AVIV_ADDRESS = new PostalAddress("תל אביב", "אבן גבירול", "69");

    /** A real Haifa address, ~85 km north. */
    private static final PostalAddress HAIFA_ADDRESS = new PostalAddress("חיפה", "הרצל", "10");

    private GoogleGeocodingProvider geocoder;
    private GoogleRoutingProvider router;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("MAPS_API_KEY");
        assertThat(apiKey)
                .as("MAPS_LIVE_TEST=true requires MAPS_API_KEY -- see this class's Javadoc")
                .isNotBlank();

        MapsProperties properties = new MapsProperties();
        properties.setMode(MapsProperties.MODE_GOOGLE);
        properties.setApiKey(apiKey);
        // Generous relative to production: a live test measures whether the provider answers
        // correctly, not whether it answers within a customer-facing latency budget.
        properties.setTimeoutMs(10_000);

        var restClient = new MapsClientConfig().mapsRestClient(properties);
        geocoder = new GoogleGeocodingProvider(restClient, properties);
        router = new GoogleRoutingProvider(restClient, properties);
    }

    @Test
    void aRealHebrewTelAvivAddressResolvesInsideTelAviv() {
        GeocodeResult result = geocoder.geocode(TEL_AVIV_ADDRESS);

        assertThat(result.isResolved())
                .as("a real Hebrew street address must resolve precisely enough to pass the "
                        + "ROOFTOP/RANGE_INTERPOLATED filter")
                .isTrue();
        assertThat(GeoDistance.kilometers(result.coordinates(), GeoCoordinates.of(32.0853, 34.7818)))
                .as("must land within Tel Aviv, not at a national centroid")
                .isLessThan(8.0);
    }

    @Test
    void aRealHebrewHaifaAddressResolvesInsideHaifa() {
        GeocodeResult result = geocoder.geocode(HAIFA_ADDRESS);

        assertThat(result.isResolved()).isTrue();
        assertThat(GeoDistance.kilometers(result.coordinates(), GeoCoordinates.of(32.7940, 34.9896)))
                .isLessThan(10.0);
    }

    @Test
    void anAddressThatDoesNotExistDoesNotQuietlyResolveToSomewherePlausible() {
        GeocodeResult result = geocoder.geocode(
                new PostalAddress("תל אביב", "רחוב שלא קיים בשום מקום כלל", "12345"));

        assertThat(result.isResolved())
                .as("the precision filter must reject the locality centroid Google falls back to")
                .isFalse();
    }

    @Test
    void aSameCityRouteReturnsPlausibleRealDrivingFigures() {
        GeoCoordinates from = geocoder.geocode(TEL_AVIV_ADDRESS).coordinates();
        GeoCoordinates to = GeoCoordinates.of(32.0770, 34.7739); // Dizengoff 10

        RouteResult route = router.route(from, to, Instant.now());

        assertThat(route.available()).isTrue();
        assertThat(route.trafficAware()).isTrue();
        assertThat(route.distanceKm().doubleValue()).isBetween(0.5, 15.0);
        assertThat(route.etaMinutes()).isBetween(1, 60);
    }

    @Test
    void anIntercityRouteIsSubstantiallyLongerThanAnIntraCityOne() {
        GeoCoordinates telAviv = geocoder.geocode(TEL_AVIV_ADDRESS).coordinates();
        GeoCoordinates haifa = geocoder.geocode(HAIFA_ADDRESS).coordinates();

        RouteResult route = router.route(haifa, telAviv, Instant.now());

        assertThat(route.available()).isTrue();
        assertThat(route.distanceKm().doubleValue())
                .as("Haifa to Tel Aviv by road")
                .isBetween(70.0, 130.0);
        assertThat(route.etaMinutes()).isBetween(45, 180);
    }

    /**
     * The N+1 answer, verified against the real API: many origins, one request. If Google ever
     * stopped accepting this shape, the platform would silently fall back to nothing rather than
     * to per-origin calls — so it is worth confirming live.
     */
    @Test
    void manyOriginsAreAnsweredInOneMatrixRequest() {
        GeoCoordinates destination = GeoCoordinates.of(32.0770, 34.7739);
        Map<Long, GeoCoordinates> origins = new LinkedHashMap<>();
        origins.put(1L, GeoCoordinates.of(32.0853, 34.7818));
        origins.put(2L, GeoCoordinates.of(32.1624, 34.8443));
        origins.put(3L, GeoCoordinates.of(31.9730, 34.7925));
        origins.put(4L, GeoCoordinates.of(32.0684, 34.8248));

        Map<Long, RouteResult> results = router.routeMatrix(origins, destination, Instant.now());

        assertThat(results).containsOnlyKeys(1L, 2L, 3L, 4L);
        assertThat(results.values()).allMatch(RouteResult::available);
        // Herzliya (2) is genuinely further from central Tel Aviv than Ramat Gan (4).
        assertThat(results.get(2L).distanceMeters()).isGreaterThan(results.get(4L).distanceMeters());
    }
}
