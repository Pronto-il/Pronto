package com.pronto.maps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coordinate validation — the guarantee that an out-of-range position cannot exist as a value at
 * all, so no routing call, geofence check or persist has to re-check one.
 */
class GeoCoordinatesTest {

    @ParameterizedTest
    @CsvSource({
            "0.0, 0.0",
            "32.0853, 34.7818",   // Tel Aviv
            "31.2530, 34.7915",   // Beer Sheva
            "90.0, 180.0",        // the corners are legal
            "-90.0, -180.0"
    })
    void acceptsEveryCoordinateInsideTheWgs84Range(String latitude, String longitude) {
        assertThatCode(() -> new GeoCoordinates(new BigDecimal(latitude), new BigDecimal(longitude)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "90.000001, 0.0",
            "-90.000001, 0.0",
            "0.0, 180.000001",
            "0.0, -180.000001",
            "1000.0, 1000.0"
    })
    void rejectsAnythingOutsideIt(String latitude, String longitude) {
        assertThatThrownBy(() -> new GeoCoordinates(new BigDecimal(latitude), new BigDecimal(longitude)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void halfACoordinatePairIsNotACoordinate() {
        assertThatThrownBy(() -> new GeoCoordinates(new BigDecimal("32.0"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoCoordinates(null, new BigDecimal("34.0")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Scale is normalised on construction so a value validated in Java and a value read back from
     * a {@code NUMERIC(9,6)} column are the same value. Without this, a geofence decision could
     * differ between the request that made it and the row that recorded it.
     */
    @Test
    void normalisesToTheSameScaleTheDatabaseColumnsUse() {
        GeoCoordinates coordinates = new GeoCoordinates(new BigDecimal("32.1"), new BigDecimal("34.87654321"));

        assertThat(coordinates.latitude().scale()).isEqualTo(GeoCoordinates.SCALE);
        assertThat(coordinates.longitude().scale()).isEqualTo(GeoCoordinates.SCALE);
        assertThat(coordinates.latitude()).isEqualByComparingTo("32.100000");
        assertThat(coordinates.longitude()).isEqualByComparingTo("34.876543");
    }

    @Test
    void ofNullableIsTheNullableColumnPairReader() {
        assertThat(GeoCoordinates.ofNullable(null, null)).isNull();
        assertThat(GeoCoordinates.ofNullable(new BigDecimal("32.0"), null)).isNull();
        assertThat(GeoCoordinates.ofNullable(null, new BigDecimal("34.0"))).isNull();
        assertThat(GeoCoordinates.ofNullable(new BigDecimal("32.0"), new BigDecimal("34.0"))).isNotNull();
    }

    @Test
    void wireFormatIsLatitudeCommaLongitudeWithNoSpaces() {
        assertThat(GeoCoordinates.of(32.0853, 34.7818).toWireFormat()).isEqualTo("32.085300,34.781800");
    }

    /**
     * A professional's live position is private operational data. If it rendered itself into
     * {@code toString()}, it would leak into every interpolated log line and exception message by
     * accident — so producing the wire form has to be a deliberate call.
     */
    @Test
    void toStringDoesNotSilentlyProduceThePositionInAWireReadyForm() {
        GeoCoordinates coordinates = GeoCoordinates.of(32.0853, 34.7818);
        assertThat(coordinates.toString()).doesNotContain(coordinates.toWireFormat());
    }
}
