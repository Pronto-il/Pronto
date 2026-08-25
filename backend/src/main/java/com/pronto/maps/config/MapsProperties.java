package com.pronto.maps.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Every maps-provider tunable, in one place — {@code pronto.maps.*}, following
 * {@code sos.config.SosProperties}' and {@code ai.decision.RoutingProperties}' precedent rather
 * than scattering magic numbers through the provider classes.
 *
 * <p><b>{@link #mode} is the important one.</b> It selects which {@code GeocodingProvider}/
 * {@code RoutingProvider} beans exist, exactly as {@code pronto.email.mode} and
 * {@code pronto.storage.mode} already do for their transports. {@code fake} is a deterministic
 * offline stand-in for local development and the automated test suite;
 * {@code auth.config.ProviderModeStartupGuard} refuses to start a Production-like environment
 * with it, because a fake provider that silently survived into Production would produce
 * confident, wrong geography — which is the failure mode this whole milestone exists to end.
 */
@Component
@ConfigurationProperties(prefix = "pronto.maps")
public class MapsProperties {

    /** {@code fake} — deterministic, offline, non-production only. */
    public static final String MODE_FAKE = "fake";

    /** {@code google} — Google Maps Platform (Geocoding API + Routes API). */
    public static final String MODE_GOOGLE = "google";

    private String mode = MODE_FAKE;

    /**
     * Provider credential. Never committed, never logged, env-var-sourced only — same treatment
     * as {@code pronto.openai.api-key} and {@code pronto.jwt.secret}. Required when
     * {@link #mode} is a real provider, and startup-checked rather than merely documented.
     */
    private String apiKey = "";

    /**
     * Region bias for geocoding — an ISO 3166-1 alpha-2 code, {@code il} for this platform.
     * A <b>bias</b>, not a filter: it tips an ambiguous street name towards Israel without
     * refusing anything, which is why {@code PostalAddress#toQuery()} also appends the country
     * explicitly.
     */
    private String region = "il";

    /**
     * Language for the provider's own formatted-address strings. Hebrew, matching the app —
     * though note these strings are diagnostic only and never overwrite the customer's own
     * address text.
     */
    private String language = "he";

    /**
     * Per-request timeout, milliseconds. Bounds a customer-facing request thread against an
     * unresponsive provider — the same reason {@code pronto.email.timeout-ms} exists. Kept
     * deliberately short: a listing that takes eight seconds to admit it has no ETA is worse
     * than one that says so in two.
     */
    private int timeoutMs = 4000;

    /**
     * How many origins may go into one route-matrix request.
     *
     * <p>Sized well below the provider's own ceiling on purpose. The binding constraint is not
     * the API limit, it is the pool: SOS dispatches at most {@code candidate-pool-size} (8, or
     * 15 for an emergency) professionals and expands to at most 40, and a normal listing is
     * pre-filtered by category, coverage and eligibility before anything is routed. 25 keeps the
     * overwhelming majority of real requests to a single call while capping the blast radius of
     * one bad request.
     */
    private int matrixBatchSize = 25;

    /**
     * How many candidates may be routed for a single listing/dispatch evaluation, in total,
     * across every batch.
     *
     * <p><b>The hard stop on provider-call explosion.</b> Business filters run first and
     * normally leave far fewer than this; if some future category genuinely has 300 eligible
     * professionals in one city, this caps what one search may cost, and the overflow is
     * reported as unavailable-with-a-reason rather than silently dropped or silently billed.
     * The truncation is logged — see {@code TravelEstimateService}.
     */
    private int maxRoutedCandidates = 50;

    /**
     * Road-distance cache TTL, seconds. Generous by design: the road network between two points
     * does not change on a human timescale, so re-asking is pure cost. 24 hours.
     */
    private int distanceCacheTtlSeconds = 86_400;

    /**
     * Traffic-aware duration cache TTL, seconds. Deliberately short, and deliberately a separate
     * knob from the distance TTL: caching a traffic-aware ETA for hours would reintroduce
     * precisely the thing MS2 removes — a confident number that is not currently true. Three
     * minutes is long enough to absorb a customer refreshing a listing and a client poll, short
     * enough that rush hour actually moves the figure.
     */
    private int trafficDurationCacheTtlSeconds = 180;

    /**
     * Geocode cache/reuse horizon, days. Resolved coordinates are persisted next to the address
     * they came from and reused while the address is unchanged; this bounds how long that reuse
     * lasts before the address is resolved again.
     *
     * <p>Set to 30 by default because <b>Google Maps Platform's terms restrict how long its
     * geocoding content may be cached</b>. This is an operational/legal constraint, not a
     * technical one, and it is a property rather than a constant so that the answer can be
     * changed without a code change if the contract in force says something different. See the
     * MS2 report's provider-decision section — the exact current terms must be confirmed against
     * live provider documentation before production launch.
     */
    private int geocodeCacheMaxAgeDays = 30;

    /** Bound on the in-process route cache, in entries. */
    private int routeCacheMaxEntries = 5000;

    /**
     * Provider endpoint bases. Defaulted to the real Google endpoints and almost never set.
     *
     * <p>They exist as properties for two concrete reasons rather than as generality for its own
     * sake. First, the provider contract tests
     * ({@code maps.google.GoogleMapsProviderContractTest}) point them at a local stub HTTP server,
     * which is how request shape, response parsing and every error mapping are exercised without
     * the automated suite depending on internet access — roadmap §36. Second, a deployment behind
     * an egress proxy or a regional endpoint can be pointed at it without a code change.
     */
    private String geocodingBaseUrl = "https://maps.googleapis.com/maps/api/geocode/json";

    private String routeMatrixUrl = "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix";

    @PostConstruct
    void validate() {
        if (mode == null || mode.isBlank()) {
            throw new IllegalStateException("Refusing to start: pronto.maps.mode (MAPS_MODE) must be set.");
        }
        String normalized = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!MODE_FAKE.equals(normalized) && !MODE_GOOGLE.equals(normalized)) {
            throw new IllegalStateException("Refusing to start: pronto.maps.mode (MAPS_MODE) must be one of '"
                    + MODE_FAKE + "' or '" + MODE_GOOGLE + "', but was '" + mode + "'.");
        }
        requirePositive("timeout-ms", timeoutMs);
        requirePositive("matrix-batch-size", matrixBatchSize);
        requirePositive("max-routed-candidates", maxRoutedCandidates);
        requirePositive("route-cache-max-entries", routeCacheMaxEntries);
        requirePositive("geocode-cache-max-age-days", geocodeCacheMaxAgeDays);
        if (distanceCacheTtlSeconds < 0 || trafficDurationCacheTtlSeconds < 0) {
            throw new IllegalStateException("Refusing to start: pronto.maps cache TTLs must not be negative "
                    + "(0 disables that cache).");
        }
        if (matrixBatchSize > maxRoutedCandidates) {
            throw new IllegalStateException("Refusing to start: pronto.maps.matrix-batch-size (" + matrixBatchSize
                    + ") exceeds pronto.maps.max-routed-candidates (" + maxRoutedCandidates + "), which would make "
                    + "the batch size unreachable and is almost certainly a configuration mistake.");
        }
    }

    private static void requirePositive(String property, int value) {
        if (value <= 0) {
            throw new IllegalStateException("Refusing to start: pronto.maps." + property
                    + " must be greater than zero, but was " + value + ".");
        }
    }

    /** Normalized, so callers never have to think about case or stray whitespace. */
    public String normalizedMode() {
        return mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean isFakeMode() {
        return MODE_FAKE.equals(normalizedMode());
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getApiKey() {
        return apiKey == null ? "" : apiKey.trim();
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMatrixBatchSize() {
        return matrixBatchSize;
    }

    public void setMatrixBatchSize(int matrixBatchSize) {
        this.matrixBatchSize = matrixBatchSize;
    }

    public int getMaxRoutedCandidates() {
        return maxRoutedCandidates;
    }

    public void setMaxRoutedCandidates(int maxRoutedCandidates) {
        this.maxRoutedCandidates = maxRoutedCandidates;
    }

    public int getDistanceCacheTtlSeconds() {
        return distanceCacheTtlSeconds;
    }

    public void setDistanceCacheTtlSeconds(int distanceCacheTtlSeconds) {
        this.distanceCacheTtlSeconds = distanceCacheTtlSeconds;
    }

    public int getTrafficDurationCacheTtlSeconds() {
        return trafficDurationCacheTtlSeconds;
    }

    public void setTrafficDurationCacheTtlSeconds(int trafficDurationCacheTtlSeconds) {
        this.trafficDurationCacheTtlSeconds = trafficDurationCacheTtlSeconds;
    }

    public int getGeocodeCacheMaxAgeDays() {
        return geocodeCacheMaxAgeDays;
    }

    public void setGeocodeCacheMaxAgeDays(int geocodeCacheMaxAgeDays) {
        this.geocodeCacheMaxAgeDays = geocodeCacheMaxAgeDays;
    }

    public int getRouteCacheMaxEntries() {
        return routeCacheMaxEntries;
    }

    public void setRouteCacheMaxEntries(int routeCacheMaxEntries) {
        this.routeCacheMaxEntries = routeCacheMaxEntries;
    }

    public String getGeocodingBaseUrl() {
        return geocodingBaseUrl;
    }

    public void setGeocodingBaseUrl(String geocodingBaseUrl) {
        this.geocodingBaseUrl = geocodingBaseUrl;
    }

    public String getRouteMatrixUrl() {
        return routeMatrixUrl;
    }

    public void setRouteMatrixUrl(String routeMatrixUrl) {
        this.routeMatrixUrl = routeMatrixUrl;
    }
}
