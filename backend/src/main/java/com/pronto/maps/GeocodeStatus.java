package com.pronto.maps;

/**
 * The four distinguishable outcomes of trying to resolve an address to coordinates.
 *
 * <p><b>Why four and not "coordinates or null".</b> A bare {@code null} conflates situations
 * that call for opposite behaviour, and the one that matters most is the difference between
 * {@link #FAILED} and {@link #UNAVAILABLE}: retrying an address the provider has already said
 * does not exist burns quota forever and never succeeds, while <em>not</em> retrying an address
 * that only failed because the provider was briefly unreachable leaves a perfectly valid
 * customer permanently unroutable. Persisted as-is
 * ({@code users.default_geocode_status}, {@code sos_requests.geocode_status}).
 *
 * <p>Deliberately the smallest representation that supports the flows MS2 actually has — this
 * is a status column, not a workflow engine.
 */
public enum GeocodeStatus {

    /**
     * Never attempted, or attempted against an address that has since been edited. The address
     * is a candidate for geocoding right now.
     */
    PENDING,

    /** Coordinates are known and current for this exact address text. */
    RESOLVED,

    /**
     * The provider answered, and the answer was "no such place" (or one too vague to be usable
     * — see {@code GoogleGeocodingProvider}'s precision rule). <b>Terminal for this address
     * text.</b> Retrying the identical string produces the identical answer; only an edit,
     * which changes the address hash and resets this to {@link #PENDING}, can help.
     */
    FAILED,

    /**
     * The provider could not be reached, timed out, rate-limited us, or returned an error.
     * Says nothing about the address. Retrying later is correct and expected.
     */
    UNAVAILABLE;

    /** True when coordinates resolved from this status may be used for routing/geofencing. */
    public boolean isUsable() {
        return this == RESOLVED;
    }

    /** True when another attempt at the same address text could plausibly succeed. */
    public boolean isRetryable() {
        return this == PENDING || this == UNAVAILABLE;
    }
}
