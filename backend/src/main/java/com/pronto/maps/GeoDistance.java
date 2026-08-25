package com.pronto.maps;

/**
 * Great-circle (Haversine) distance between two points, in metres.
 *
 * <p><b>What this is for, and what it is deliberately not for.</b> This answers "is this person
 * physically near that address" — the arrival geofence check ({@code bookings}), and the cheap
 * pre-filter that keeps candidates who are obviously nowhere near a job out of the routing
 * request entirely. It does <b>not</b> answer "how far must they drive" or "how long will it
 * take": straight-line distance across the Mediterranean is not a road, and no customer-facing
 * distance or ETA figure in this platform is ever derived from this class. Those come from
 * {@link RoutingProvider} and nowhere else.
 *
 * <p><b>Why Haversine is sufficient for proximity.</b> It assumes a spherical Earth, which
 * costs up to ~0.5% against the WGS-84 ellipsoid — at a 150 m arrival radius that is well under
 * a metre, orders of magnitude smaller than the GPS accuracy the same check already tolerates.
 * Calling a paid routing API to decide whether somebody is standing at a door would be slower,
 * cost money per arrival, fail during a provider outage, and be <em>less</em> correct (road
 * distance from a point to itself is not zero when the nearest road segment is 40 m away).
 *
 * <p>Pure, static, no I/O — genuinely, unlike the pre-MS2 Javadoc on
 * {@code matching.DistanceEtaStrategy}, which said so long after it stopped being true.
 */
public final class GeoDistance {

    /**
     * IUGG mean Earth radius, metres. The conventional Haversine constant; using the equatorial
     * radius instead would bias every result ~0.3% long.
     */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoDistance() {
    }

    /** Great-circle distance in metres. Always {@code >= 0}; {@code 0} for identical points. */
    public static double meters(GeoCoordinates from, GeoCoordinates to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both endpoints are required to measure a distance.");
        }
        double lat1 = Math.toRadians(from.latitudeAsDouble());
        double lat2 = Math.toRadians(to.latitudeAsDouble());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(to.longitudeAsDouble() - from.longitudeAsDouble());

        double sinHalfLat = Math.sin(deltaLat / 2);
        double sinHalfLon = Math.sin(deltaLon / 2);
        double a = sinHalfLat * sinHalfLat + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;
        // atan2 rather than asin: numerically stable for antipodal points, where a rounds to 1
        // and asin(sqrt(a)) loses precision.
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1 - a)));
        return EARTH_RADIUS_METERS * c;
    }

    /** Convenience for the pre-filter, which thinks in kilometres like the rest of matching. */
    public static double kilometers(GeoCoordinates from, GeoCoordinates to) {
        return meters(from, to) / 1000.0;
    }
}
