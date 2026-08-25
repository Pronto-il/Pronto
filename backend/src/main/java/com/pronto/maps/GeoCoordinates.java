package com.pronto.maps;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A validated WGS-84 point. The single coordinate type in this codebase — every column that
 * holds a position ({@code professional_locations}, {@code orders.service_*},
 * {@code sos_requests}, {@code users.default_*}) stores exactly this shape, at exactly this
 * precision.
 *
 * <p><b>Validation happens in the constructor, not at the call site.</b> There is no way to
 * hold an out-of-range {@code GeoCoordinates}: a nonsensical position becomes impossible to
 * pass into routing, into a geofence check, or into a persist, rather than becoming something
 * every one of those has to remember to re-check.
 *
 * <p><b>{@link BigDecimal}, not {@code double}</b> — matching the {@code NUMERIC(9,6)} columns
 * these round-trip through. {@code double} would make persisted values differ from the value
 * that was validated, which is exactly the kind of drift a geofence decision must not have.
 * The trigonometry in {@link GeoDistance} converts to {@code double} deliberately and locally.
 */
public record GeoCoordinates(BigDecimal latitude, BigDecimal longitude) {

    /** Matches {@code NUMERIC(9,6)} everywhere these are stored. ~11 cm at the equator. */
    public static final int SCALE = 6;

    public GeoCoordinates {
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException("Coordinates require both a latitude and a longitude.");
        }
        if (latitude.compareTo(new BigDecimal("-90")) < 0 || latitude.compareTo(new BigDecimal("90")) > 0) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90, but was " + latitude + ".");
        }
        if (longitude.compareTo(new BigDecimal("-180")) < 0 || longitude.compareTo(new BigDecimal("180")) > 0) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180, but was " + longitude + ".");
        }
        latitude = latitude.setScale(SCALE, RoundingMode.HALF_UP);
        longitude = longitude.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static GeoCoordinates of(double latitude, double longitude) {
        return new GeoCoordinates(BigDecimal.valueOf(latitude), BigDecimal.valueOf(longitude));
    }

    /**
     * {@code null} in, {@code null} out — for the many callers reading a nullable column pair
     * where "no coordinates" is a legitimate, expected state rather than an error. A caller
     * with only one of the two gets {@code null}, not a half-formed point.
     */
    public static GeoCoordinates ofNullable(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) {
            return null;
        }
        return new GeoCoordinates(latitude, longitude);
    }

    public double latitudeAsDouble() {
        return latitude.doubleValue();
    }

    public double longitudeAsDouble() {
        return longitude.doubleValue();
    }

    /**
     * {@code lat,lng} with no spaces — the wire format Google's Geocoding and Routes APIs both
     * accept, and the form used to build cache keys.
     *
     * <p>Note that this is deliberately NOT {@code toString()}: a coordinate pair is private
     * operational data (a professional's live position), and having it render itself into
     * every interpolated log line and exception message by accident is precisely the leak
     * {@code docs/production-roadmap} §40 forbids. Producing it has to be a decision.
     */
    public String toWireFormat() {
        return latitude.toPlainString() + "," + longitude.toPlainString();
    }
}
