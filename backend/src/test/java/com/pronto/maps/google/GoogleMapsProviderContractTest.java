package com.pronto.maps.google;

import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.GeocodeResult;
import com.pronto.maps.GeocodeStatus;
import com.pronto.maps.MapsProviderException;
import com.pronto.maps.PostalAddress;
import com.pronto.maps.RouteResult;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.maps.config.MapsClientConfig;
import com.pronto.maps.config.MapsProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>Provider integration tests</b> (roadmap §36) — the real Google client classes, exercised
 * end-to-end over HTTP against a local stub that replays real Google response shapes.
 *
 * <p><b>No internet access.</b> Every case runs against a {@link HttpServer} bound to a loopback
 * port, so the ordinary test suite has no external dependency, no API key and no cost — while
 * still exercising the genuine request construction, the genuine HTTP client (including its
 * timeouts), and the genuine response parsing and error mapping. A stub that returned parsed
 * objects would test none of that.
 *
 * <p>The response bodies below are transcribed from the documented Geocoding API and Routes API
 * formats. Live verification against the real service is a separate, explicitly-tagged test
 * ({@code GoogleMapsLiveApiTest}) and a documented step in the MS2 report.
 */
class GoogleMapsProviderContractTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final PostalAddress DIZENGOFF = new PostalAddress("תל אביב", "דיזנגוף", "10");
    private static final GeoCoordinates DESTINATION = GeoCoordinates.of(32.0853, 34.7818);
    private static final GeoCoordinates ORIGIN_NEAR = GeoCoordinates.of(32.1000, 34.8000);
    private static final GeoCoordinates ORIGIN_FAR = GeoCoordinates.of(32.7940, 34.9896);

    private HttpServer server;
    private MapsProperties properties;
    private GoogleGeocodingProvider geocoder;
    private GoogleRoutingProvider router;

    /** The last request body the stub received, so request construction can be asserted. */
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestUri = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        setUpFresh();
    }

    /** Extracted so one test can restart the stub mid-method (contexts cannot be re-registered). */
    private void setUpFresh() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();

        properties = new MapsProperties();
        properties.setMode(MapsProperties.MODE_GOOGLE);
        properties.setApiKey("test-key");
        properties.setTimeoutMs(1500);
        properties.setGeocodingBaseUrl(baseUrl() + "/geocode");
        properties.setRouteMatrixUrl(baseUrl() + "/routematrix");

        var restClient = new MapsClientConfig().mapsRestClient(properties);
        geocoder = new GoogleGeocodingProvider(restClient, properties);
        router = new GoogleRoutingProvider(restClient, properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Respond to {@code path} with {@code status} and {@code body}. */
    private void stub(String path, int status, String body) {
        server.createContext(path, exchange -> {
            requestCount.incrementAndGet();
            lastRequestUri.set(exchange.getRequestURI().toString());
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, status, body);
        });
    }

    /**
     * A handler that answers far too late, so the client's read timeout is what ends the call.
     *
     * <p>The delay is only comfortably longer than {@code pronto.maps.timeout-ms} (1.5 s here),
     * not arbitrarily long: the handler thread keeps running after the client gives up, and a
     * ten-second sleep would add ten seconds to the suite for each of these cases to prove
     * something a three-second one proves equally well.
     */
    private void stubHang(String path) {
        server.createContext(path, exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // =======================================================================================
    // Geocoding
    // =======================================================================================

    @Test
    void aRealIsraeliAddressResolvesToItsCoordinates() {
        // Transcribed from the live response, components included. A fixture without
        // address_components is not a real Google response, and MS2's live validation found
        // exactly what that omission hides -- see GoogleAddressMatch.
        stub("/geocode", 200, """
                {"status":"OK","results":[{
                  "formatted_address":"דיזנגוף 10, תל אביב-יפו, ישראל",
                  "geometry":{"location":{"lat":32.0770,"lng":34.7739},"location_type":"ROOFTOP"},
                  "types":["street_address"],
                  "address_components":[
                    {"long_name":"10","types":["street_number"]},
                    {"long_name":"דיזנגוף","types":["route"]},
                    {"long_name":"תל אביב-יפו","types":["locality","political"]},
                    {"long_name":"ישראל","types":["country","political"]}]
                }]}""");

        GeocodeResult result = geocoder.geocode(DIZENGOFF);

        assertThat(result.isResolved()).isTrue();
        assertThat(result.coordinates().latitude()).isEqualByComparingTo("32.077000");
        assertThat(result.coordinates().longitude()).isEqualByComparingTo("34.773900");
        assertThat(result.formattedAddress()).contains("דיזנגוף");
    }

    @Test
    void theQueryCarriesTheAddressTheRegionBiasAndTheLanguage() {
        stub("/geocode", 200, """
                {"status":"OK","results":[{"geometry":{"location":{"lat":32.0,"lng":34.0},
                "location_type":"ROOFTOP"}}]}""");

        geocoder.geocode(DIZENGOFF);

        assertThat(lastRequestUri.get()).contains("region=il").contains("language=he").contains("key=test-key");
    }

    @Test
    void anAddressGoogleHasNeverHeardOfIsFailedNotUnavailable() {
        // FAILED is terminal for this text -- retrying will produce the same answer, so the caller
        // must not keep spending quota on it.
        stub("/geocode", 200, """
                {"status":"ZERO_RESULTS","results":[]}""");

        assertThat(geocoder.geocode(DIZENGOFF).status()).isEqualTo(GeocodeStatus.FAILED);
    }

    /**
     * <b>The precision rule.</b> Google answers a nonexistent street in a real city with the city
     * itself, marked {@code APPROXIMATE}. Accepting that would put back exactly what MS2 removes:
     * a confident coordinate that is really a locality centroid.
     */
    @Test
    void aLocalityCentroidIsRefusedRatherThanAcceptedAsAnAddress() {
        stub("/geocode", 200, """
                {"status":"OK","results":[{
                  "formatted_address":"Tel Aviv-Yafo, Israel",
                  "geometry":{"location":{"lat":32.0853,"lng":34.7818},"location_type":"APPROXIMATE"}
                }]}""");

        assertThat(geocoder.geocode(DIZENGOFF).isResolved()).isFalse();
    }

    @Test
    void aStreetCentroidIsAlsoRefused() {
        // GEOMETRIC_CENTER on a long street can be a kilometre from the house number asked for.
        stub("/geocode", 200, """
                {"status":"OK","results":[{
                  "geometry":{"location":{"lat":32.08,"lng":34.78},"location_type":"GEOMETRIC_CENTER"}
                }]}""");

        assertThat(geocoder.geocode(DIZENGOFF).isResolved()).isFalse();
    }

    @Test
    void anInterpolatedHouseNumberIsAcceptedBecauseItIsPreciseEnough() {
        stub("/geocode", 200, """
                {"status":"OK","results":[{
                  "geometry":{"location":{"lat":32.0770,"lng":34.7739},"location_type":"RANGE_INTERPOLATED"},
                  "address_components":[
                    {"long_name":"10","types":["street_number"]},
                    {"long_name":"דיזנגוף","types":["route"]},
                    {"long_name":"תל אביב-יפו","types":["locality","political"]}]
                }]}""");

        assertThat(geocoder.geocode(DIZENGOFF).isResolved()).isTrue();
    }

    /** Google returns candidates best-first, but "first" and "first acceptable" differ. */
    @Test
    void theFirstSufficientlyPreciseCandidateIsTakenRatherThanTheFirstCandidate() {
        stub("/geocode", 200, """
                {"status":"OK","results":[
                  {"geometry":{"location":{"lat":32.0853,"lng":34.7818},"location_type":"APPROXIMATE"},
                   "address_components":[
                     {"long_name":"תל אביב-יפו","types":["locality","political"]}]},
                  {"geometry":{"location":{"lat":32.0770,"lng":34.7739},"location_type":"ROOFTOP"},
                   "address_components":[
                     {"long_name":"10","types":["street_number"]},
                     {"long_name":"דיזנגוף","types":["route"]},
                     {"long_name":"תל אביב-יפו","types":["locality","political"]}]}
                ]}""");

        GeocodeResult result = geocoder.geocode(DIZENGOFF);

        assertThat(result.isResolved()).isTrue();
        assertThat(result.coordinates().latitude()).isEqualByComparingTo("32.077000");
    }

    /**
     * <b>The MS2 live defect, replayed end to end through the provider.</b>
     *
     * This is verbatim what Google returned for
     * {@code רחוב שלא קיים בשום מקום כלל 12345, תל אביב, ישראל}: a locality, a POI, and — third —
     * a real {@code ROOFTOP} building on an entirely unrelated street. The geometry-only filter
     * scanned past the first two and accepted the third, so a customer whose address does not
     * exist would have had a professional dispatched to Raoul Wallenberg 36 and an arrival
     * geofence verified against it.
     *
     * <p>This test is the regression guard at the level the defect actually occurred.
     */
    @Test
    void aNonexistentStreetIsNotResolvedToARealBuildingOnAnotherStreet() {
        stub("/geocode", 200, """
                {"status":"OK","results":[
                  {"formatted_address":"תל אביב-יפו, ישראל",
                   "geometry":{"location":{"lat":32.0853,"lng":34.7818},"location_type":"APPROXIMATE"},
                   "types":["locality","political"],"partial_match":true,
                   "address_components":[
                     {"long_name":"תל אביב-יפו","types":["locality","political"]},
                     {"long_name":"ישראל","types":["country","political"]}]},
                  {"formatted_address":"תל אביב-יפו, ישראל",
                   "geometry":{"location":{"lat":32.0853,"lng":34.7818},"location_type":"GEOMETRIC_CENTER"},
                   "types":["establishment","point_of_interest"],"partial_match":true,
                   "address_components":[
                     {"long_name":"תל אביב-יפו","types":["locality","political"]},
                     {"long_name":"ישראל","types":["country","political"]}]},
                  {"formatted_address":"ראול ולנברג 36, תל אביב-יפו, ישראל",
                   "geometry":{"location":{"lat":32.1093,"lng":34.8368},"location_type":"ROOFTOP"},
                   "types":["establishment","point_of_interest"],"partial_match":true,
                   "address_components":[
                     {"long_name":"36","types":["street_number"]},
                     {"long_name":"ראול ולנברג","types":["route"]},
                     {"long_name":"תל אביב-יפו","types":["locality","political"]},
                     {"long_name":"ישראל","types":["country","political"]}]}
                ]}""");

        GeocodeResult result = geocoder.geocode(
                new PostalAddress("תל אביב", "רחוב שלא קיים בשום מקום כלל", "12345"));

        // FAILED, not UNAVAILABLE: Google answered perfectly well, the address simply is not one.
        assertThat(result.isResolved()).isFalse();
        assertThat(result.status()).isEqualTo(GeocodeStatus.FAILED);
        assertThat(result.coordinates()).isNull();
    }

    /**
     * The same rule from the other side: a real street in the wrong city. Google returns the real
     * building in the city it is actually in, flagged {@code partial_match}, which is precisely
     * the kind of "helpful" reinterpretation that must not become a dispatch address.
     */
    @Test
    void aRealStreetInTheWrongCityIsNotResolved() {
        stub("/geocode", 200, """
                {"status":"OK","results":[
                  {"formatted_address":"חיפה, ישראל",
                   "geometry":{"location":{"lat":32.7940,"lng":34.9896},"location_type":"APPROXIMATE"},
                   "partial_match":true,
                   "address_components":[{"long_name":"חיפה","types":["locality","political"]}]},
                  {"formatted_address":"דיזנגוף 10, תל אביב-יפו, ישראל",
                   "geometry":{"location":{"lat":32.0770,"lng":34.7739},"location_type":"ROOFTOP"},
                   "types":["street_address"],"partial_match":true,
                   "address_components":[
                     {"long_name":"10","types":["street_number"]},
                     {"long_name":"דיזנגוף","types":["route"]},
                     {"long_name":"תל אביב-יפו","types":["locality","political"]}]}
                ]}""");

        GeocodeResult result = geocoder.geocode(new PostalAddress("חיפה", "דיזנגוף", "10"));

        assertThat(result.status()).isEqualTo(GeocodeStatus.FAILED);
    }

    /**
     * A real street with an impossible house number. Google falls back to a street centroid with
     * {@code partial_match} FALSE, so only the geometry filter catches this one — worth proving at
     * the provider level, because it is the case the new component rules would pass on their own.
     */
    @Test
    void aRealStreetWithAnImpossibleHouseNumberIsNotResolved() {
        stub("/geocode", 200, """
                {"status":"OK","results":[{
                  "formatted_address":"דיזנגוף, תל אביב-יפו, ישראל",
                  "geometry":{"location":{"lat":32.0806,"lng":34.7737},"location_type":"GEOMETRIC_CENTER"},
                  "types":["route"],
                  "address_components":[
                    {"long_name":"דיזנגוף","types":["route"]},
                    {"long_name":"תל אביב-יפו","types":["locality","political"]}]
                }]}""");

        assertThat(geocoder.geocode(new PostalAddress("תל אביב", "דיזנגוף", "99999")).status())
                .isEqualTo(GeocodeStatus.FAILED);
    }

    /**
     * The whole point of the FAILED/UNAVAILABLE split, restated after the fix: an invalid address
     * and an unreachable provider must not converge on the same outcome. One is terminal for that
     * text, the other is worth retrying.
     */
    @Test
    void anInvalidAddressAndAnUnreachableProviderRemainDifferentOutcomes() throws IOException {
        stub("/geocode", 200, """
                {"status":"OK","results":[{
                  "geometry":{"location":{"lat":32.1093,"lng":34.8368},"location_type":"ROOFTOP"},
                  "partial_match":true,
                  "address_components":[
                    {"long_name":"36","types":["street_number"]},
                    {"long_name":"ראול ולנברג","types":["route"]},
                    {"long_name":"תל אביב-יפו","types":["locality","political"]}]
                }]}""");
        assertThat(geocoder.geocode(DIZENGOFF).status()).isEqualTo(GeocodeStatus.FAILED);

        tearDown();
        setUpFresh();
        stubHang("/geocode");
        assertThat(geocoder.geocode(DIZENGOFF).status()).isEqualTo(GeocodeStatus.UNAVAILABLE);
    }

    @Test
    void aQuotaRefusalIsUnavailableNotFailed_becauseItSaysNothingAboutTheAddress() {
        stub("/geocode", 200, """
                {"status":"OVER_QUERY_LIMIT","results":[]}""");

        assertThat(geocoder.geocode(DIZENGOFF).status()).isEqualTo(GeocodeStatus.UNAVAILABLE);
    }

    /** A rejected key is a deployment fault: loud, and identical on every subsequent call. */
    @Test
    void aRejectedApiKeyIsAConfigurationFaultNotADataOutcome() {
        stub("/geocode", 200, """
                {"status":"REQUEST_DENIED","error_message":"The provided API key is invalid.","results":[]}""");

        assertThatThrownBy(() -> geocoder.geocode(DIZENGOFF))
                .isInstanceOf(MapsProviderException.class)
                .hasMessageContaining("MAPS_API_KEY");
    }

    @Test
    void aGeocoderTimeoutIsUnavailable() {
        stubHang("/geocode");

        assertThat(geocoder.geocode(DIZENGOFF).status()).isEqualTo(GeocodeStatus.UNAVAILABLE);
    }

    @Test
    void aServerErrorIsUnavailable() {
        stub("/geocode", 503, "{}");

        assertThat(geocoder.geocode(DIZENGOFF).status()).isEqualTo(GeocodeStatus.UNAVAILABLE);
    }

    @Test
    void anAddressWithNoStreetIsRefusedWithoutSpendingARequest() {
        stub("/geocode", 200, "{\"status\":\"OK\",\"results\":[]}");

        assertThat(geocoder.geocode(new PostalAddress("תל אביב", null, null)).isResolved()).isFalse();
        assertThat(requestCount.get()).isZero();
    }

    // =======================================================================================
    // Routing
    // =======================================================================================

    @Test
    void aSameCityRouteReturnsRealRoadDistanceAndDrivingDuration() {
        stub("/routematrix", 200, """
                [{"originIndex":0,"destinationIndex":0,"distanceMeters":7350,
                  "duration":"1080s","condition":"ROUTE_EXISTS"}]""");

        RouteResult result = router.route(ORIGIN_NEAR, DESTINATION, NOW);

        assertThat(result.available()).isTrue();
        assertThat(result.distanceMeters()).isEqualTo(7350);
        assertThat(result.durationSeconds()).isEqualTo(1080);
        assertThat(result.distanceKm()).isEqualByComparingTo("7.4");
        assertThat(result.etaMinutes()).isEqualTo(18);
        assertThat(result.trafficAware()).isTrue();
    }

    @Test
    void anIntercityRouteReturnsProportionallyLargerFigures() {
        stub("/routematrix", 200, """
                [{"originIndex":0,"destinationIndex":0,"distanceMeters":94200,
                  "duration":"4320s","condition":"ROUTE_EXISTS"}]""");

        RouteResult result = router.route(ORIGIN_FAR, DESTINATION, NOW);

        assertThat(result.distanceKm()).isEqualByComparingTo("94.2");
        assertThat(result.etaMinutes()).isEqualTo(72);
    }

    @Test
    void theRequestAsksForDrivingAndForTrafficAwareDurations() {
        stub("/routematrix", 200, """
                [{"originIndex":0,"distanceMeters":100,"duration":"60s","condition":"ROUTE_EXISTS"}]""");

        router.route(ORIGIN_NEAR, DESTINATION, NOW);

        assertThat(lastRequestBody.get()).contains("\"travelMode\":\"DRIVE\"");
        assertThat(lastRequestBody.get()).contains("\"routingPreference\":\"TRAFFIC_AWARE\"");
    }

    /**
     * Google rejects a departure time in the past, and "now" becomes the past between building the
     * request and it landing. Omitting it means "now", which is what almost every call wants.
     */
    @Test
    void aDepartureOfNowIsOmittedRatherThanSentAsAPastTimestamp() {
        stub("/routematrix", 200, """
                [{"originIndex":0,"distanceMeters":100,"duration":"60s","condition":"ROUTE_EXISTS"}]""");

        router.route(ORIGIN_NEAR, DESTINATION, Instant.now());

        assertThat(lastRequestBody.get()).doesNotContain("departureTime");
    }

    @Test
    void agenuinelyFutureDepartureIsForwarded() {
        stub("/routematrix", 200, """
                [{"originIndex":0,"distanceMeters":100,"duration":"60s","condition":"ROUTE_EXISTS"}]""");

        router.route(ORIGIN_NEAR, DESTINATION, Instant.now().plusSeconds(3600));

        assertThat(lastRequestBody.get()).contains("departureTime");
    }

    /**
     * Elements are matched back to caller keys by {@code originIndex}, never by array position —
     * a provider that reorders or omits an element must not silently attribute one professional's
     * route to another.
     */
    @Test
    void elementsAreMatchedByOriginIndexEvenWhenTheyArriveOutOfOrder() {
        stub("/routematrix", 200, """
                [{"originIndex":1,"distanceMeters":90000,"duration":"4000s","condition":"ROUTE_EXISTS"},
                 {"originIndex":0,"distanceMeters":5000,"duration":"600s","condition":"ROUTE_EXISTS"}]""");

        Map<Long, GeoCoordinates> origins = new LinkedHashMap<>();
        origins.put(11L, ORIGIN_NEAR);
        origins.put(22L, ORIGIN_FAR);

        Map<Long, RouteResult> results = router.routeMatrix(origins, DESTINATION, NOW);

        assertThat(results.get(11L).distanceMeters()).isEqualTo(5000);
        assertThat(results.get(22L).distanceMeters()).isEqualTo(90000);
    }

    @Test
    void anElementTheProviderOmittedBecomesAnUnavailableResultNotAMissingEntry() {
        stub("/routematrix", 200, """
                [{"originIndex":0,"distanceMeters":5000,"duration":"600s","condition":"ROUTE_EXISTS"}]""");

        Map<Long, GeoCoordinates> origins = new LinkedHashMap<>();
        origins.put(11L, ORIGIN_NEAR);
        origins.put(22L, ORIGIN_FAR);

        Map<Long, RouteResult> results = router.routeMatrix(origins, DESTINATION, NOW);

        assertThat(results).containsOnlyKeys(11L, 22L);
        assertThat(results.get(22L).available()).isFalse();
        assertThat(results.get(22L).unavailableReason()).isEqualTo(RouteUnavailableReason.PROVIDER_UNAVAILABLE);
    }

    @Test
    void aPerElementFailureDoesNotAffectTheOtherCandidatesInTheSameBatch() {
        stub("/routematrix", 200, """
                [{"originIndex":0,"status":{"code":3,"message":"bad waypoint"}},
                 {"originIndex":1,"distanceMeters":5000,"duration":"600s","condition":"ROUTE_EXISTS"}]""");

        Map<Long, GeoCoordinates> origins = new LinkedHashMap<>();
        origins.put(11L, ORIGIN_NEAR);
        origins.put(22L, ORIGIN_FAR);

        Map<Long, RouteResult> results = router.routeMatrix(origins, DESTINATION, NOW);

        assertThat(results.get(11L).available()).isFalse();
        assertThat(results.get(22L).available()).isTrue();
    }

    /** Rare but real — an island, a coordinate in the sea. Not an outage, and reported separately. */
    @Test
    void aPointPairWithNoDrivableRouteIsNoRouteNotAnOutage() {
        stub("/routematrix", 200, """
                [{"originIndex":0,"condition":"ROUTE_NOT_FOUND"}]""");

        assertThat(router.route(ORIGIN_NEAR, DESTINATION, NOW).unavailableReason())
                .isEqualTo(RouteUnavailableReason.NO_ROUTE);
    }

    /**
     * A "route exists" element carrying no figures must not default to zero — that would read as
     * "they are already here" and could verify an arrival that never happened.
     */
    @Test
    void anElementWithNoFiguresIsUnavailableRatherThanZeroDistance() {
        stub("/routematrix", 200, """
                [{"originIndex":0,"condition":"ROUTE_EXISTS"}]""");

        RouteResult result = router.route(ORIGIN_NEAR, DESTINATION, NOW);

        assertThat(result.available()).isFalse();
        assertThat(result.distanceMeters()).isNull();
    }

    @Test
    void aRoutingTimeoutBecomesUnavailableForEveryOriginInTheBatch() {
        stubHang("/routematrix");

        Map<Long, GeoCoordinates> origins = new LinkedHashMap<>();
        origins.put(11L, ORIGIN_NEAR);
        origins.put(22L, ORIGIN_FAR);

        Map<Long, RouteResult> results = router.routeMatrix(origins, DESTINATION, NOW);

        assertThat(results).containsOnlyKeys(11L, 22L);
        assertThat(results.values()).allSatisfy(r -> {
            assertThat(r.available()).isFalse();
            assertThat(r.unavailableReason()).isEqualTo(RouteUnavailableReason.PROVIDER_UNAVAILABLE);
        });
    }

    @Test
    void aRateLimitResponseIsATransientOutcomeNotAConfigurationFault() {
        stub("/routematrix", 429, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}");

        assertThat(router.route(ORIGIN_NEAR, DESTINATION, NOW).unavailableReason())
                .isEqualTo(RouteUnavailableReason.PROVIDER_UNAVAILABLE);
    }

    @Test
    void aForbiddenResponseIsAConfigurationFaultAndIsThrown() {
        stub("/routematrix", 403, "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\"}}");

        assertThatThrownBy(() -> router.route(ORIGIN_NEAR, DESTINATION, NOW))
                .isInstanceOf(MapsProviderException.class)
                .hasMessageContaining("MAPS_API_KEY");
    }

    @Test
    void aBatchLargerThanTheConfiguredSizeIsSplitIntoSeveralRequests() {
        properties.setMatrixBatchSize(2);
        stub("/routematrix", 200, """
                [{"originIndex":0,"distanceMeters":5000,"duration":"600s","condition":"ROUTE_EXISTS"}]""");

        Map<Long, GeoCoordinates> origins = new LinkedHashMap<>();
        for (long id = 1; id <= 5; id++) {
            origins.put(id, GeoCoordinates.of(32.0 + id / 100.0, 34.8));
        }

        Map<Long, RouteResult> results = router.routeMatrix(origins, DESTINATION, NOW);

        // 5 origins at 2 per request = 3 requests, and every key still accounted for.
        assertThat(requestCount.get()).isEqualTo(3);
        assertThat(results).hasSize(5);
    }

    // ---- protobuf duration parsing ----

    @Test
    void protobufDurationsAreParsedIncludingFractionalSeconds() {
        assertThat(GoogleRoutingProvider.parseProtobufDuration("1832s")).isEqualTo(1832);
        assertThat(GoogleRoutingProvider.parseProtobufDuration("1832.500s")).isEqualTo(1832);
        assertThat(GoogleRoutingProvider.parseProtobufDuration("0s")).isZero();
    }

    @Test
    void anUnparseableDurationIsNullRatherThanZero() {
        assertThat(GoogleRoutingProvider.parseProtobufDuration("about half an hour")).isNull();
        assertThat(GoogleRoutingProvider.parseProtobufDuration("")).isNull();
        assertThat(GoogleRoutingProvider.parseProtobufDuration(null)).isNull();
    }
}
