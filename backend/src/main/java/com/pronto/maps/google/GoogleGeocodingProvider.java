package com.pronto.maps.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.GeocodeResult;
import com.pronto.maps.GeocodingProvider;
import com.pronto.maps.MapsProviderException;
import com.pronto.maps.PostalAddress;
import com.pronto.maps.config.MapsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;

/**
 * {@link GeocodingProvider} backed by the Google Maps Platform Geocoding API.
 *
 * <p>Active only when {@code pronto.maps.mode=google}. See the {@code maps} package README for
 * why Google was chosen over AWS Location Service for this platform.
 *
 * <h2>Correspondence checking — the part that matters</h2>
 *
 * Google answers <em>something</em> for almost any string, because its geocoder is an interpreter
 * rather than a validator: it silently drops tokens it cannot use and returns its best guess.
 * Asked for a street that does not exist in a city that does, it returns the city as an
 * {@code APPROXIMATE} locality — <b>and also, further down the same {@code results} array, a real
 * building on an unrelated street at {@code ROOFTOP} precision.</b>
 *
 * <p>Filtering on geometry alone therefore is not enough, and MS2's live validation proved it:
 * the nonsense query {@code רחוב שלא קיים בשום מקום כלל 12345, תל אביב} was accepted as
 * {@code ראול ולנברג 36}. Precision of the answer says nothing about correspondence to the
 * question. Every candidate is now judged by {@link GoogleAddressMatch} against the structured
 * {@code address_components} — route, locality, street number, and Google's own
 * {@code partial_match} flag — all of which this class previously discarded.
 *
 * <p>Anything that fails is reported as {@link GeocodeResult#failed()} — "we could not resolve
 * this address", which is true, rather than "here is where it is", which would not be.
 */
@Component
@ConditionalOnProperty(name = "pronto.maps.mode", havingValue = MapsProperties.MODE_GOOGLE)
public class GoogleGeocodingProvider implements GeocodingProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleGeocodingProvider.class);

    static final String PROVIDER_NAME = "google";

    /** Google's own status strings. */
    private static final String STATUS_OK = "OK";
    private static final String STATUS_ZERO_RESULTS = "ZERO_RESULTS";
    private static final String STATUS_REQUEST_DENIED = "REQUEST_DENIED";
    private static final String STATUS_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String STATUS_OVER_QUERY_LIMIT = "OVER_QUERY_LIMIT";
    private static final String STATUS_OVER_DAILY_LIMIT = "OVER_DAILY_LIMIT";

    private final RestClient restClient;
    private final MapsProperties properties;

    public GoogleGeocodingProvider(@Qualifier("mapsRestClient") RestClient restClient, MapsProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public GeocodeResult geocode(PostalAddress address) {
        if (address == null || !address.isGeocodable()) {
            // Not an error and not worth a round trip: a caller asking to resolve an address with
            // no street is asking for a centroid, which this provider would refuse anyway.
            return GeocodeResult.failed();
        }

        long startedAt = System.nanoTime();
        JsonNode body;
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(properties.getGeocodingBaseUrl())
                    .queryParam("address", address.toQuery())
                    .queryParam("region", properties.getRegion())
                    .queryParam("language", properties.getLanguage())
                    .queryParam("key", properties.getApiKey())
                    .build()
                    .encode()
                    .toUri();
            body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            // Timeout, DNS failure, connection reset, 5xx. Says nothing about the address.
            // The exception message is logged, never the address or the URI (which carries the
            // API key as a query parameter).
            log.warn("maps.geocode.failed provider={} outcome=UNAVAILABLE cause={} latencyMs={}",
                    PROVIDER_NAME, e.getClass().getSimpleName(), elapsedMs(startedAt));
            return GeocodeResult.unavailable();
        }

        if (body == null) {
            log.warn("maps.geocode.failed provider={} outcome=UNAVAILABLE cause=empty-body latencyMs={}",
                    PROVIDER_NAME, elapsedMs(startedAt));
            return GeocodeResult.unavailable();
        }

        String status = body.path("status").asText("");
        switch (status) {
            case STATUS_OK -> {
                GeocodeResult result = firstAcceptableResult(address, body);
                log.info("maps.geocode.ok provider={} outcome={} latencyMs={}",
                        PROVIDER_NAME, result.status(), elapsedMs(startedAt));
                return result;
            }
            case STATUS_ZERO_RESULTS -> {
                log.info("maps.geocode.ok provider={} outcome=FAILED cause=zero-results latencyMs={}",
                        PROVIDER_NAME, elapsedMs(startedAt));
                return GeocodeResult.failed();
            }
            case STATUS_OVER_QUERY_LIMIT, STATUS_OVER_DAILY_LIMIT -> {
                // Quota, not address. Retryable, and loud: this is a billing/limits condition an
                // operator must see rather than a per-address curiosity.
                log.error("maps.geocode.failed provider={} outcome=UNAVAILABLE cause=quota status={} latencyMs={}",
                        PROVIDER_NAME, status, elapsedMs(startedAt));
                return GeocodeResult.unavailable();
            }
            case STATUS_REQUEST_DENIED, STATUS_INVALID_REQUEST -> {
                // A deployment fault: a missing/restricted/unbilled key, or a malformed request.
                // Every subsequent call fails identically, so this is exceptional rather than an
                // outcome -- see MapsProviderException's Javadoc. error_message is Google's own
                // diagnostic text and contains no customer data.
                throw new MapsProviderException(PROVIDER_NAME, "Google Geocoding API refused the request (status="
                        + status + ", error_message=" + body.path("error_message").asText("none")
                        + "). Check MAPS_API_KEY, its API restrictions, and that billing is enabled.");
            }
            default -> {
                log.warn("maps.geocode.failed provider={} outcome=UNAVAILABLE cause=unknown-status status={} "
                        + "latencyMs={}", PROVIDER_NAME, status, elapsedMs(startedAt));
                return GeocodeResult.unavailable();
            }
        }
    }

    /**
     * The first candidate that is both precise <em>and</em> actually the address we asked for.
     *
     * <p>Google returns candidates best-first, so scanning in order is the correct reading — but
     * "the first result", "the first precise result" and "the first <b>correct</b> result" are
     * three different things, and MS2's live validation caught this code stopping at the second.
     * The nonsense query that exposed it produced, as its third candidate, a genuine
     * {@code ROOFTOP} building on an unrelated street; every candidate is now put through
     * {@link GoogleAddressMatch#judge}, which compares Google's structured
     * {@code address_components} against what the customer typed.
     *
     * <p>The rejection reason of the best (first) candidate is logged, because "we could not
     * resolve this address" is a very different operational signal depending on whether the cause
     * was a coarse result, a street that does not match, or a house number that does not exist.
     * The reason code is logged; the address never is.
     */
    private GeocodeResult firstAcceptableResult(PostalAddress requested, JsonNode body) {
        GoogleAddressMatch.Verdict bestVerdict = null;

        for (JsonNode candidate : body.path("results")) {
            GoogleAddressMatch.Verdict verdict = GoogleAddressMatch.judge(requested, candidate);
            if (bestVerdict == null) {
                bestVerdict = verdict;
            }
            if (!verdict.isAcceptable()) {
                continue;
            }
            JsonNode location = candidate.path("geometry").path("location");
            try {
                GeoCoordinates coordinates = new GeoCoordinates(
                        new BigDecimal(location.get("lat").asText()),
                        new BigDecimal(location.get("lng").asText()));
                return GeocodeResult.resolved(coordinates, candidate.path("formatted_address").asText(null));
            } catch (IllegalArgumentException | NullPointerException e) {
                // Out-of-range or unparseable numbers from the provider: treat as no result rather
                // than propagating a malformed coordinate into a column.
                log.warn("maps.geocode.rejected provider={} cause=malformed-coordinates", PROVIDER_NAME);
            }
        }

        // Google matched something, but nothing that is the address we asked for. FAILED, not
        // UNAVAILABLE: asking again produces the same non-answer, so retrying only spends quota.
        log.info("maps.geocode.rejected provider={} outcome=FAILED reason={} candidates={}",
                PROVIDER_NAME, bestVerdict == null ? "NO_RESULTS" : bestVerdict, body.path("results").size());
        return GeocodeResult.failed();
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isFake() {
        return false;
    }
}
