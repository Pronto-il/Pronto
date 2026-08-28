package com.pronto.maps.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.maps.SelectedPlace;
import com.pronto.maps.service.SelectedPlaceValidator.FieldNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The backend half of "typing an address is not enough".
 *
 * <p>The cases that matter here are the <b>partial</b> ones. "A complete, valid selection is
 * accepted" is the easy half and shows up the first time anybody registers; what this class is
 * really for is the payload shape the UI would never produce — half a claim, a fabricated
 * coordinate, a place on another continent — because those are what arrive when the client is a
 * stale tab, a replayed draft, or {@code curl}.
 */
class SelectedPlaceValidatorTest {

    private static final FieldNames NESTED = FieldNames.nested("customer.defaultAddress.");
    private static final FieldNames CAMEL = FieldNames.camelCase("service");

    /** Dizengoff 100, Tel Aviv — inside the service area. */
    private static final BigDecimal LAT = new BigDecimal("32.081100");
    private static final BigDecimal LNG = new BigDecimal("34.773900");
    private static final String PLACE_ID = "ChIJN1t_tDeuEmsRUsoyG83frY4";

    private final SelectedPlaceValidator validator = new SelectedPlaceValidator();

    // ---- the accepted case -----------------------------------------------------

    @Test
    void aCompleteSelectionIsAccepted() {
        SelectedPlace place = validator.requireSelected(PLACE_ID, "דיזנגוף 100, תל אביב-יפו",
                LAT, LNG, NESTED);

        assertThat(place.placeId()).isEqualTo(PLACE_ID);
        assertThat(place.formattedAddress()).isEqualTo("דיזנגוף 100, תל אביב-יפו");
        assertThat(place.coordinates().latitude()).isEqualByComparingTo(LAT);
        assertThat(place.coordinates().longitude()).isEqualByComparingTo(LNG);
    }

    @Test
    void aSelectionWithNoFormattedAddressIsStillAccepted() {
        // Google does not guarantee one for every place, and it is diagnostic rather than
        // load-bearing -- refusing the whole address over it would be refusing a real selection.
        SelectedPlace place = validator.requireSelected(PLACE_ID, null, LAT, LNG, NESTED);

        assertThat(place.formattedAddress()).isNull();
    }

    // ---- free text only --------------------------------------------------------

    @Test
    void freeTextWithNoSelectionIsRefusedWhereASelectionIsRequired() {
        assertThatThrownBy(() -> validator.requireSelected(null, null, null, null, NESTED))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(fieldNames(e)).containsExactly("customer.defaultAddress.placeId");
                });
    }

    @ParameterizedTest(name = "a blank placeId ({0}) counts as no selection")
    @ValueSource(strings = {"", " ", "\t"})
    void aBlankPlaceIdIsNotASelection(String blank) {
        assertThatThrownBy(() -> validator.requireSelected(blank, null, null, null, NESTED))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void freeTextWithNoSelectionIsAllowedWhereASelectionIsOptional() {
        // The grandfathering path: a legacy default address carries no place id, and this must not
        // be an error -- BookingsService/SosService decide, not this class.
        assertThat(validator.validateOptional(null, null, null, null, CAMEL)).isNull();
    }

    // ---- partial claims --------------------------------------------------------

    @Test
    void coordinatesWithoutAPlaceIdAreRefused() {
        // The most important refusal in this file. A client that sends coordinates it derived
        // some other way -- or replayed from a previous address -- must not have them stored in
        // the columns that mean "the customer selected this".
        assertThatThrownBy(() -> validator.validateOptional(null, null, LAT, LNG, CAMEL))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(fieldNames(e)).containsExactly("servicePlaceId"));
    }

    @Test
    void aPlaceIdWithoutCoordinatesIsRefused() {
        assertThatThrownBy(() -> validator.validateOptional(PLACE_ID, null, null, null, CAMEL))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(fieldNames(e)).containsExactly("serviceLatitude"));
    }

    @ParameterizedTest(name = "a place id with only {0} of a coordinate pair is refused")
    @CsvSource({"32.0811,", ",34.7739"})
    void aPlaceIdWithHalfACoordinatePairIsRefused(BigDecimal latitude, BigDecimal longitude) {
        assertThatThrownBy(() -> validator.validateOptional(PLACE_ID, null, latitude, longitude, CAMEL))
                .isInstanceOf(ApiException.class);
    }

    // ---- fabricated coordinates ------------------------------------------------

    @ParameterizedTest(name = "({0}, {1}) is outside the service area")
    @CsvSource({
            "0.0, 0.0",             // Null Island -- what a client library produces for "unknown"
            "51.5074, -0.1278",     // London
            "40.7128, -74.0060",    // New York
            "34.7739, 32.0811",     // the Tel Aviv pair with latitude and longitude swapped
            "25.2048, 55.2708",     // Dubai -- close-ish, still not the service area
    })
    void aSelectionOutsideTheServiceAreaIsRefused(BigDecimal latitude, BigDecimal longitude) {
        assertThatThrownBy(() -> validator.validateOptional(PLACE_ID, null, latitude, longitude, CAMEL))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(fieldNames(e)).containsExactly("servicePlaceId");
                });
    }

    @ParameterizedTest(name = "({0}, {1}) is a real Israeli address and is accepted")
    @CsvSource({
            "32.0811, 34.7739",     // Tel Aviv
            "31.7683, 35.2137",     // Jerusalem
            "32.7940, 34.9896",     // Haifa
            "29.5577, 34.9519",     // Eilat -- the southern edge
            "33.2080, 35.5700",     // Metula -- the northern edge
    })
    void realAddressesAcrossTheCountryAreAccepted(BigDecimal latitude, BigDecimal longitude) {
        // The negative test above is only meaningful next to this one: a bounding box that
        // rejected Eilat or Metula would "pass" every fabrication test and break real customers at
        // both ends of the country.
        assertThat(validator.validateOptional(PLACE_ID, null, latitude, longitude, CAMEL)).isNotNull();
    }

    @Test
    void anOutOfRangeCoordinateIsA400NotA500() {
        // GeoCoordinates throws IllegalArgumentException, which would surface as an INTERNAL_ERROR
        // if it escaped this boundary. A client sending latitude 91 made a bad request.
        assertThatThrownBy(() -> validator.validateOptional(PLACE_ID, null,
                new BigDecimal("91.0"), LNG, CAMEL))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(fieldNames(e)).containsExactly("serviceLatitude");
                });
    }

    // ---- oversized input -------------------------------------------------------

    @Test
    void anOversizedPlaceIdIsRefusedRatherThanTruncatedByTheDatabase() {
        assertThatThrownBy(() -> validator.validateOptional("x".repeat(256), null, LAT, LNG, CAMEL))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(fieldNames(e)).containsExactly("servicePlaceId"));
    }

    @Test
    void anOversizedFormattedAddressIsRefused() {
        assertThatThrownBy(() -> validator.validateOptional(PLACE_ID, "x".repeat(501), LAT, LNG, CAMEL))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(fieldNames(e)).containsExactly("serviceFormattedAddress"));
    }

    // ---- field naming ----------------------------------------------------------

    @Test
    void fieldNamesMatchTheWireShapesTheClientsActuallySend() {
        // A field error naming a field the client did not send renders nowhere, so the frontend
        // shows a generic failure and the customer cannot tell what to fix.
        assertThat(NESTED.placeId()).isEqualTo("customer.defaultAddress.placeId");
        assertThat(NESTED.formattedAddress()).isEqualTo("customer.defaultAddress.formattedAddress");
        assertThat(NESTED.latitude()).isEqualTo("customer.defaultAddress.latitude");

        assertThat(CAMEL.placeId()).isEqualTo("servicePlaceId");
        assertThat(CAMEL.formattedAddress()).isEqualTo("serviceFormattedAddress");
        assertThat(CAMEL.latitude()).isEqualTo("serviceLatitude");
    }

    @SuppressWarnings("unchecked")
    private static List<String> fieldNames(Throwable e) {
        Object details = ((ApiException) e).getDetails();
        if (details == null) {
            return List.of();
        }
        return ((List<FieldError>) details).stream().map(FieldError::field).toList();
    }
}
