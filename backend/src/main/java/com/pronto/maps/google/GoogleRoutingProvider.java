package com.pronto.maps.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.MapsProviderException;
import com.pronto.maps.RouteResult;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.maps.RoutingProvider;
import com.pronto.maps.config.MapsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RoutingProvider} backed by the Google Maps Platform <b>Routes API</b>
 * ({@code computeRouteMatrix}).
 *
 * <p>Active only when {@code pronto.maps.mode=google}.
 *
 * <h2>Everything goes through the matrix endpoint, including single routes</h2>
 *
 * {@link #route} delegates to {@link #routeMatrix} with one origin rather than calling a
 * separate single-route endpoint. One request-building path, one response-parsing path, one
 * error taxonomy — a second code path exercised only by the less common call site is a second
 * code path that gets its failure handling wrong.
 *
 * <h2>Traffic</h2>
 *
 * {@code routingPreference: TRAFFIC_AWARE} makes the returned duration account for current
 * traffic conditions, which is what replaces the old hardcoded peak-hour surcharge. Note the
 * asymmetry MS2 requires: a provider-derived duration is used exactly as given, and if the
 * provider ever stops supplying a traffic-aware one the platform reports the plain duration
 * honestly rather than adding an invented adjustment back on top.
 *
 * <h2>Per-element failures</h2>
 *
 * A matrix response is not all-or-nothing: individual elements carry their own status and
 * {@code condition}. An element that failed, or that reports {@code ROUTE_NOT_FOUND}, becomes an
 * unavailable {@link RouteResult} for that one key — the other candidates in the same batch are
 * unaffected, and no key is ever dropped from the returned map.
 */
@Component
@ConditionalOnProperty(name = "pronto.maps.mode", havingValue = MapsProperties.MODE_GOOGLE)
public class GoogleRoutingProvider implements RoutingProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleRoutingProvider.class);

    static final String PROVIDER_NAME = "google";

    /**
     * The response fields actually needed. Routes API <b>requires</b> an explicit field mask and
     * bills by the fields requested, so asking for less is both mandatory and cheaper — omitting
     * {@code condition} in particular would leave "no route exists" indistinguishable from a
     * zero-distance route.
     */
    private static final String FIELD_MASK =
            "originIndex,destinationIndex,duration,distanceMeters,status,condition";

    /**
     * Google requires a {@code departureTime} to be in the future when supplied. A caller asking
     * for "leaving now" therefore must not supply one at all — the API defaults to request time,
     * which is exactly right, and passing {@code Instant.now()} would intermittently fail as a
     * past timestamp by the time the request lands. Only a departure at least this far ahead is
     * forwarded.
     */
    private static final Duration MIN_FUTURE_DEPARTURE = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final MapsProperties properties;

    public GoogleRoutingProvider(@Qualifier("mapsRestClient") RestClient restClient, MapsProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public RouteResult route(GeoCoordinates origin, GeoCoordinates destination, Instant departureTime) {
        if (origin == null) {
            return RouteResult.unavailable(RouteUnavailableReason.PROFESSIONAL_LOCATION_MISSING);
        }
        if (destination == null) {
            return RouteResult.unavailable(RouteUnavailableReason.DESTINATION_UNKNOWN);
        }
        Map<String, RouteResult> single = routeMatrix(Map.of("only", origin), destination, departureTime);
        return single.getOrDefault("only", RouteResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE));
    }

    @Override
    public <K> Map<K, RouteResult> routeMatrix(Map<K, GeoCoordinates> originsByKey, GeoCoordinates destination,
                                                Instant departureTime) {
        Map<K, RouteResult> results = new LinkedHashMap<>();
        if (originsByKey == null || originsByKey.isEmpty()) {
            return results;
        }
        if (destination == null) {
            originsByKey.keySet()
                    .forEach(key -> results.put(key, RouteResult.unavailable(RouteUnavailableReason.DESTINATION_UNKNOWN)));
            return results;
        }

        // Positional correspondence is unavoidable on the wire -- Google answers with
        // originIndex -- so it is confined to exactly here: one ordered key list, and every
        // response element resolved back through it. No caller ever sees an index.
        List<K> keys = new ArrayList<>(originsByKey.keySet());
        for (int start = 0; start < keys.size(); start += properties.getMatrixBatchSize()) {
            int end = Math.min(keys.size(), start + properties.getMatrixBatchSize());
            List<K> chunk = keys.subList(start, end);
            results.putAll(routeChunk(chunk, originsByKey, destination, departureTime));
        }
        return results;
    }

    private <K> Map<K, RouteResult> routeChunk(List<K> keys, Map<K, GeoCoordinates> originsByKey,
                                                GeoCoordinates destination, Instant departureTime) {
        Map<K, RouteResult> results = new LinkedHashMap<>();
        long startedAt = System.nanoTime();

        JsonNode body;
        try {
            body = restClient.post()
                    .uri(properties.getRouteMatrixUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Goog-Api-Key", properties.getApiKey())
                    .header("X-Goog-FieldMask", FIELD_MASK)
                    .body(buildRequest(keys, originsByKey, destination, departureTime))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        // 401/403 are configuration faults; everything else is treated as a
                        // transient outcome by the catch below, which rethrows only the former.
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) {
                            throw new MapsProviderException(PROVIDER_NAME,
                                    "Google Routes API refused the request (HTTP " + status + "). Check "
                                            + "MAPS_API_KEY, that the Routes API is enabled for it, and that its "
                                            + "API/referrer restrictions permit this deployment.");
                        }
                        throw new IllegalStateException("Routes API HTTP " + status);
                    })
                    .body(JsonNode.class);
        } catch (MapsProviderException e) {
            throw e;
        } catch (Exception e) {
            log.warn("maps.route.failed provider={} outcome=UNAVAILABLE origins={} cause={} latencyMs={}",
                    PROVIDER_NAME, keys.size(), e.getClass().getSimpleName(), elapsedMs(startedAt));
            keys.forEach(key -> results.put(key, RouteResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE)));
            return results;
        }

        // Seed every key as unavailable, then overwrite the ones the response actually accounts
        // for. This is what guarantees the interface's "one entry per input key, always" promise
        // even if the provider silently omits an element.
        keys.forEach(key -> results.put(key, RouteResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE)));

        if (body == null || !body.isArray()) {
            log.warn("maps.route.failed provider={} outcome=UNAVAILABLE origins={} cause=unexpected-body latencyMs={}",
                    PROVIDER_NAME, keys.size(), elapsedMs(startedAt));
            return results;
        }

        int resolved = 0;
        for (JsonNode element : body) {
            int originIndex = element.path("originIndex").asInt(-1);
            if (originIndex < 0 || originIndex >= keys.size()) {
                continue;
            }
            RouteResult result = toRouteResult(element);
            if (result.available()) {
                resolved++;
            }
            results.put(keys.get(originIndex), result);
        }

        log.info("maps.route.ok provider={} origins={} resolved={} latencyMs={}",
                PROVIDER_NAME, keys.size(), resolved, elapsedMs(startedAt));
        return results;
    }

    private RouteResult toRouteResult(JsonNode element) {
        // A non-empty `status` object means this element failed. Google omits it (or sends {})
        // on success, so "has a code" is the test, not "is present".
        JsonNode status = element.path("status");
        if (status.hasNonNull("code") && status.get("code").asInt(0) != 0) {
            return RouteResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE);
        }
        String condition = element.path("condition").asText("");
        if ("ROUTE_NOT_FOUND".equals(condition)) {
            return RouteResult.unavailable(RouteUnavailableReason.NO_ROUTE);
        }
        if (!element.hasNonNull("distanceMeters") || !element.hasNonNull("duration")) {
            // ROUTE_EXISTS with no figures should not happen; treat as unavailable rather than
            // defaulting a missing distance to zero, which would read as "they are already here".
            return RouteResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE);
        }
        Integer durationSeconds = parseProtobufDuration(element.get("duration").asText(null));
        if (durationSeconds == null) {
            return RouteResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE);
        }
        return RouteResult.available(element.get("distanceMeters").asInt(), durationSeconds, true);
    }

    /**
     * Routes API serialises durations as protobuf {@code Duration} JSON: a decimal number of
     * seconds with a trailing {@code s} ({@code "1832s"}). Fractional seconds are legal and are
     * truncated — sub-second precision on a driving estimate is noise.
     */
    static Integer parseProtobufDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.endsWith("s")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            return (int) Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private <K> Map<String, Object> buildRequest(List<K> keys, Map<K, GeoCoordinates> originsByKey,
                                                  GeoCoordinates destination, Instant departureTime) {
        List<Object> origins = new ArrayList<>(keys.size());
        for (K key : keys) {
            origins.add(Map.of("waypoint", waypoint(originsByKey.get(key))));
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("origins", origins);
        request.put("destinations", List.of(Map.of("waypoint", waypoint(destination))));
        request.put("travelMode", "DRIVE");
        request.put("routingPreference", "TRAFFIC_AWARE");
        // See MIN_FUTURE_DEPARTURE: omitted means "now", which is what almost every call wants.
        if (departureTime != null && departureTime.isAfter(Instant.now().plus(MIN_FUTURE_DEPARTURE))) {
            request.put("departureTime", departureTime.toString());
        }
        return request;
    }

    private Map<String, Object> waypoint(GeoCoordinates coordinates) {
        return Map.of("location", Map.of("latLng", Map.of(
                "latitude", coordinates.latitudeAsDouble(),
                "longitude", coordinates.longitudeAsDouble())));
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    @Override
    public int maxOriginsPerBatch() {
        return properties.getMatrixBatchSize();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isFake() {
        return false;
    }

    @Override
    public boolean supportsTrafficAwareDuration() {
        return true;
    }
}
