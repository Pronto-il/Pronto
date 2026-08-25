package com.pronto.maps;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The Haversine calculation behind arrival verification.
 *
 * <p>This is the only piece of MS2 geometry whose correctness a customer-visible decision rests on
 * directly: it decides whether a professional's {@code הגעתי} is accepted. The fixtures below use
 * real Israeli coordinates with independently-known separations, so a wrong Earth radius, a
 * degrees/radians slip or a swapped lat/lng would fail rather than merely look plausible.
 */
class GeoDistanceTest {

    private static final GeoCoordinates DIZENGOFF_CENTER = GeoCoordinates.of(32.0794, 34.7739);
    private static final GeoCoordinates TEL_AVIV_CITY_HALL = GeoCoordinates.of(32.0809, 34.7806);
    private static final GeoCoordinates JERUSALEM_CENTER = GeoCoordinates.of(31.7683, 35.2137);
    private static final GeoCoordinates HAIFA_CENTER = GeoCoordinates.of(32.7940, 34.9896);

    @Test
    void identicalPointsAreZeroMetresApart() {
        assertThat(GeoDistance.meters(DIZENGOFF_CENTER, DIZENGOFF_CENTER)).isZero();
    }

    @Test
    void twoPointsInCentralTelAvivAreHundredsOfMetresApart() {
        // ~0.0015 deg lat + ~0.0067 deg lng at this latitude. Roughly 650 m; asserted loosely
        // enough not to encode a spurious precision, tightly enough that a radius error fails.
        double meters = GeoDistance.meters(DIZENGOFF_CENTER, TEL_AVIV_CITY_HALL);
        assertThat(meters).isBetween(600.0, 700.0);
    }

    @Test
    void telAvivToJerusalemIsAboutFiftyFourKilometres() {
        // Straight-line, not by road (the road is ~65 km) -- which is exactly why this class is
        // never used for a customer-facing distance figure.
        assertThat(GeoDistance.kilometers(TEL_AVIV_CITY_HALL, JERUSALEM_CENTER))
                .isCloseTo(54.0, within(1.5));
    }

    @Test
    void telAvivToHaifaIsAboutEightyKilometres() {
        assertThat(GeoDistance.kilometers(TEL_AVIV_CITY_HALL, HAIFA_CENTER)).isCloseTo(80.0, within(2.0));
    }

    @Test
    void distanceIsSymmetric() {
        assertThat(GeoDistance.meters(TEL_AVIV_CITY_HALL, HAIFA_CENTER))
                .isCloseTo(GeoDistance.meters(HAIFA_CENTER, TEL_AVIV_CITY_HALL), within(0.001));
    }

    @Test
    void kilometresIsMetresDividedByAThousand() {
        assertThat(GeoDistance.kilometers(DIZENGOFF_CENTER, JERUSALEM_CENTER) * 1000)
                .isCloseTo(GeoDistance.meters(DIZENGOFF_CENTER, JERUSALEM_CENTER), within(0.001));
    }

    /**
     * The scale arrival verification actually operates at. A 150 m geofence is only meaningful if
     * the calculation is accurate at 150 m — an error that is negligible over 80 km can be
     * decisive here.
     */
    @Test
    void isAccurateAtTheScaleOfTheArrivalGeofence() {
        // 0.001 degrees of latitude is ~111.2 m anywhere on Earth.
        GeoCoordinates from = GeoCoordinates.of(32.0000, 34.8000);
        GeoCoordinates oneThousandthNorth = GeoCoordinates.of(32.0010, 34.8000);
        assertThat(GeoDistance.meters(from, oneThousandthNorth)).isCloseTo(111.2, within(0.5));
    }

    /** A longitude degree shrinks with latitude; at 32°N it is ~94 km, not 111. */
    @Test
    void accountsForLongitudeConvergingTowardsThePoles() {
        double atEquator = GeoDistance.meters(GeoCoordinates.of(0.0, 0.0), GeoCoordinates.of(0.0, 0.01));
        double atIsrael = GeoDistance.meters(GeoCoordinates.of(32.0, 34.8), GeoCoordinates.of(32.0, 34.81));
        assertThat(atIsrael).isLessThan(atEquator);
        assertThat(atIsrael / atEquator).isCloseTo(Math.cos(Math.toRadians(32.0)), within(0.01));
    }

    @Test
    void antipodalPointsDoNotProduceNaN() {
        // The reason the implementation uses atan2 rather than asin: asin(sqrt(a)) loses precision
        // as a approaches 1, and a naive version returns NaN here.
        double meters = GeoDistance.meters(GeoCoordinates.of(0.0, 0.0), GeoCoordinates.of(0.0, 180.0));
        assertThat(Double.isNaN(meters)).isFalse();
        assertThat(meters).isCloseTo(20_015_000, within(50_000.0));
    }

    @Test
    void aMissingEndpointIsRejectedRatherThanTreatedAsTheOrigin() {
        assertThatThrownBy(() -> GeoDistance.meters(null, DIZENGOFF_CENTER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeoDistance.meters(DIZENGOFF_CENTER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
