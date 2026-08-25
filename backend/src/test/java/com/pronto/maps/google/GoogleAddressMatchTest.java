package com.pronto.maps.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.maps.PostalAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address-correspondence rules, unit-tested against the exact response shapes the live
 * Geocoding API returns.
 *
 * <p><b>Every fixture below is a transcription of a real observed response</b>, captured while
 * diagnosing the defect this class exists to fix — not an invented shape. The nonsense-address
 * fixture in particular is the actual third candidate Google returned for
 * {@code רחוב שלא קיים בשום מקום כלל 12345, תל אביב}: a genuine {@code ROOFTOP} building on
 * {@code ראול ולנברג 36}, which the previous geometry-only filter accepted as the customer's
 * service address.
 *
 * <p>These are deterministic and make no network call. The live counterpart is
 * {@code GoogleMapsLiveApiTest}, which is opt-in.
 */
class GoogleAddressMatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final PostalAddress DIZENGOFF_10 = new PostalAddress("תל אביב", "דיזנגוף", "10");

    private static JsonNode candidate(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds a Geocoding API result with the given components. Any argument may be {@code null}. */
    private static JsonNode result(String locationType, Boolean partialMatch, String route,
                                    String streetNumber, String locality) {
        StringBuilder components = new StringBuilder("[");
        if (streetNumber != null) {
            components.append("{\"long_name\":\"").append(streetNumber)
                    .append("\",\"types\":[\"street_number\"]},");
        }
        if (route != null) {
            components.append("{\"long_name\":\"").append(route).append("\",\"types\":[\"route\"]},");
        }
        if (locality != null) {
            components.append("{\"long_name\":\"").append(locality)
                    .append("\",\"types\":[\"locality\",\"political\"]},");
        }
        components.append("{\"long_name\":\"ישראל\",\"types\":[\"country\",\"political\"]}]");

        return candidate("{"
                + "\"geometry\":{\"location\":{\"lat\":32.0770,\"lng\":34.7739},"
                + "\"location_type\":\"" + locationType + "\"},"
                + (partialMatch == null ? "" : "\"partial_match\":" + partialMatch + ",")
                + "\"address_components\":" + components
                + "}");
    }

    // =======================================================================================
    // 1. The legitimate case
    // =======================================================================================

    /** Transcribed from the live response for {@code דיזנגוף 10, תל אביב, ישראל}. */
    @Test
    void anExactHebrewStreetAndHouseNumberResolves() {
        JsonNode live = candidate("""
                {"formatted_address":"דיזנגוף 10, תל אביב-יפו, ישראל",
                 "geometry":{"location":{"lat":32.0806,"lng":34.7737},"location_type":"ROOFTOP"},
                 "types":["street_address"],
                 "address_components":[
                   {"long_name":"10","types":["street_number"]},
                   {"long_name":"דיזנגוף","types":["route"]},
                   {"long_name":"תל אביב-יפו","types":["locality","political"]},
                   {"long_name":"ישראל","types":["country","political"]}]}""");

        assertThat(GoogleAddressMatch.judge(DIZENGOFF_10, live))
                .isEqualTo(GoogleAddressMatch.Verdict.OK);
    }

    // =======================================================================================
    // 2-5. The wrong cases
    // =======================================================================================

    /**
     * <b>The live defect, reproduced exactly.</b> Google's actual third candidate for the nonsense
     * query: real building, rooftop precision, unrelated street, unrelated number.
     */
    @Test
    void theLiveDefect_aRoofTopBuildingOnAnUnrelatedStreet_isRejected() {
        JsonNode live = candidate("""
                {"formatted_address":"ראול ולנברג 36, תל אביב-יפו, ישראל",
                 "geometry":{"location":{"lat":32.1093,"lng":34.8368},"location_type":"ROOFTOP"},
                 "types":["establishment","point_of_interest"],
                 "partial_match":true,
                 "address_components":[
                   {"long_name":"36","types":["street_number"]},
                   {"long_name":"ראול ולנברג","types":["route"]},
                   {"long_name":"תל אביב-יפו","types":["locality","political"]},
                   {"long_name":"ישראל","types":["country","political"]}]}""");

        PostalAddress nonsense = new PostalAddress("תל אביב", "רחוב שלא קיים בשום מקום כלל", "12345");

        assertThat(GoogleAddressMatch.judge(nonsense, live))
                .isEqualTo(GoogleAddressMatch.Verdict.PARTIAL_MATCH);
    }

    /** And it is still rejected on the street alone, with the partial-match flag removed. */
    @Test
    void aDifferentReturnedStreetIsRejectedEvenWithoutThePartialMatchFlag() {
        assertThat(GoogleAddressMatch.judge(DIZENGOFF_10,
                result("ROOFTOP", false, "ראול ולנברג", "10", "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.STREET_MISMATCH);
    }

    /**
     * Transcribed from the live response for {@code דיזנגוף 10, חיפה} — Google returns the real
     * Tel Aviv building rather than admitting there is no such Haifa address.
     */
    @Test
    void aDifferentReturnedCityIsRejected() {
        assertThat(GoogleAddressMatch.judge(new PostalAddress("חיפה", "דיזנגוף", "10"),
                result("ROOFTOP", false, "דיזנגוף", "10", "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.CITY_MISMATCH);
    }

    @Test
    void aDifferentExplicitHouseNumberIsRejected() {
        assertThat(GoogleAddressMatch.judge(DIZENGOFF_10,
                result("ROOFTOP", false, "דיזנגוף", "250", "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.STREET_NUMBER_MISMATCH);
    }

    /** A locality or POI result carries no route at all. */
    @Test
    void aResultWithNoRouteComponentIsRejected() {
        assertThat(GoogleAddressMatch.judge(DIZENGOFF_10,
                result("ROOFTOP", false, null, null, "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.MISSING_ROUTE);
    }

    /**
     * <b>The fake-precision case.</b> A house number was asked for and the result identifies only
     * the street. Reporting that as a resolved service address would put a geofence centre
     * somewhere along a street rather than at a building.
     */
    @Test
    void aMissingStreetNumberIsRejectedWhenOneWasRequested() {
        assertThat(GoogleAddressMatch.judge(DIZENGOFF_10,
                result("ROOFTOP", false, "דיזנגוף", null, "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.MISSING_STREET_NUMBER);
    }

    /** But an address that genuinely has no number does not need one back. */
    @Test
    void aMissingStreetNumberIsFineWhenNoneWasRequested() {
        assertThat(GoogleAddressMatch.judge(new PostalAddress("תל אביב", "דיזנגוף", null),
                result("ROOFTOP", false, "דיזנגוף", null, "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.OK);
    }

    // =======================================================================================
    // 6-7. Google's own signals
    // =======================================================================================

    @Test
    void aPartialMatchIsRejectedEvenWhenEveryComponentLooksRight() {
        // Google only sets this when it did not match the query as given. Every legitimate live
        // probe had it absent or false; every wrong one had it true.
        assertThat(GoogleAddressMatch.judge(DIZENGOFF_10,
                result("ROOFTOP", true, "דיזנגוף", "10", "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.PARTIAL_MATCH);
    }

    @Test
    void anAbsentPartialMatchFieldIsTreatedAsNotPartial() {
        // Google omits the field entirely on a clean match rather than sending false.
        assertThat(GoogleAddressMatch.judge(DIZENGOFF_10,
                result("ROOFTOP", null, "דיזנגוף", "10", "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.OK);
    }

    @ParameterizedTest
    @CsvSource({"APPROXIMATE", "GEOMETRIC_CENTER", "'' "})
    void coarseGeometryIsStillRejected(String locationType) {
        assertThat(GoogleAddressMatch.judge(DIZENGOFF_10,
                result(locationType.trim(), false, "דיזנגוף", "10", "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.IMPRECISE_GEOMETRY);
    }

    /**
     * The live probe for {@code דיזנגוף 99999} — a real street, an impossible number. Google falls
     * back to a street centroid with {@code partial_match} FALSE, so the geometry filter is the
     * only thing that catches it. Worth an explicit test: it is the one wrong case the new
     * component rules would let through on their own.
     */
    @Test
    void anImpossibleHouseNumberFallingBackToAStreetCentroidIsRejected() {
        assertThat(GoogleAddressMatch.judge(new PostalAddress("תל אביב", "דיזנגוף", "99999"),
                result("GEOMETRIC_CENTER", false, "דיזנגוף", null, "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.IMPRECISE_GEOMETRY);
    }

    @Test
    void aResultWithNoUsableCoordinatesIsRejected() {
        assertThat(GoogleAddressMatch.judge(DIZENGOFF_10, candidate("""
                {"geometry":{"location":{},"location_type":"ROOFTOP"},
                 "address_components":[{"long_name":"דיזנגוף","types":["route"]}]}""")))
                .isEqualTo(GoogleAddressMatch.Verdict.MALFORMED);
    }

    // =======================================================================================
    // 9. Normalisation must not reject legitimate formatting differences
    // =======================================================================================

    /**
     * Live evidence: asked for {@code אבן גבירול 69}, Google returns route
     * {@code שלמה אבן גבירול} — its fuller formal name. Rejecting this would break a real address
     * the live test itself uses.
     */
    @Test
    void googleReturningTheFullerFormalStreetNameIsAccepted() {
        assertThat(GoogleAddressMatch.judge(new PostalAddress("תל אביב", "אבן גבירול", "69"),
                result("ROOFTOP", false, "שלמה אבן גבירול", "69", "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.OK);
    }

    /** Live evidence: requested {@code תל אביב}, Google's locality is {@code תל אביב-יפו}. */
    @Test
    void googleReturningTheHyphenatedMunicipalCityNameIsAccepted() {
        assertThat(GoogleAddressMatch.judge(DIZENGOFF_10,
                result("ROOFTOP", false, "דיזנגוף", "10", "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.OK);
    }

    /** Customers type the street-type word; Google's route component does not carry it. */
    @Test
    void aCustomerWritingRechovBeforeTheStreetNameIsAccepted() {
        assertThat(GoogleAddressMatch.judge(new PostalAddress("תל אביב", "רחוב דיזנגוף", "10"),
                result("ROOFTOP", false, "דיזנגוף", "10", "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.OK);
        assertThat(GoogleAddressMatch.judge(new PostalAddress("תל אביב", "שדרות רוטשילד", "1"),
                result("ROOFTOP", false, "רוטשילד", "1", "תל אביב-יפו")))
                .isEqualTo(GoogleAddressMatch.Verdict.OK);
    }

    @Test
    void whitespaceAndCaseDifferencesAreNotMismatches() {
        assertThat(GoogleAddressMatch.judge(new PostalAddress("Tel Aviv", "  DIZENGOFF  ", " 10 "),
                result("ROOFTOP", false, "Dizengoff", "10", "Tel Aviv")))
                .isEqualTo(GoogleAddressMatch.Verdict.OK);
    }

    /** A maqaf and an ASCII hyphen are the same separator as far as a place name is concerned. */
    @Test
    void hebrewMaqafAndAsciiHyphenAreEquivalent() {
        assertThat(GoogleAddressMatch.tokensCorrespond("תל אביב", "תל־אביב־יפו")).isTrue();
        assertThat(GoogleAddressMatch.tokensCorrespond("תל-אביב", "תל אביב-יפו")).isTrue();
    }

    @Test
    void gershayimAndQuotesDoNotCauseAMismatch() {
        assertThat(GoogleAddressMatch.tokensCorrespond("שד״ל", "שדל")).isTrue();
        assertThat(GoogleAddressMatch.tokensCorrespond("ז'בוטינסקי", "זבוטינסקי")).isTrue();
    }

    // =======================================================================================
    // The line the tolerance must not cross
    // =======================================================================================

    /**
     * Subset matching is what keeps this from being fuzzy matching: it cannot bridge two names
     * that disagree on any token. These are the cases that would matter most if it could.
     */
    @Test
    void similarButDifferentPlaceNamesDoNotCorrespond() {
        assertThat(GoogleAddressMatch.tokensCorrespond("רמת גן", "רמת השרון")).isFalse();
        assertThat(GoogleAddressMatch.tokensCorrespond("כפר סבא", "כפר יונה")).isFalse();
        assertThat(GoogleAddressMatch.tokensCorrespond("הרצל", "הרצליה")).isFalse();
        assertThat(GoogleAddressMatch.tokensCorrespond("דיזנגוף", "דיזנגוף סנטר בן גוריון")).isTrue();
    }

    @Test
    void anEmptySideNeverCorresponds() {
        assertThat(GoogleAddressMatch.tokensCorrespond("", "דיזנגוף")).isFalse();
        assertThat(GoogleAddressMatch.tokensCorrespond("דיזנגוף", null)).isFalse();
        // A street that is nothing but a street-type word carries no distinguishing tokens.
        assertThat(GoogleAddressMatch.tokensCorrespond("רחוב", "דיזנגוף")).isFalse();
    }

    // ---- house numbers ----

    /**
     * Israeli house numbers carry entrance/apartment suffixes that identify a sub-unit of the same
     * building. Pronto keeps those in its own {@code apartment}/{@code entrance} fields and does
     * not need the geocoder to preserve them, so the comparison is on the building.
     */
    @ParameterizedTest
    @CsvSource({
            "10, 10",
            "12א, 12",
            "12, 12א",
            "12/4, 12",
            "07, 7",
    })
    void houseNumbersIdentifyingTheSameBuildingCorrespond(String requested, String returned) {
        assertThat(GoogleAddressMatch.houseNumbersCorrespond(requested, returned)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "12345, 36",
            "10, 100",
            "10, 1",
            "36, 63",
    })
    void houseNumbersIdentifyingDifferentBuildingsDoNotCorrespond(String requested, String returned) {
        assertThat(GoogleAddressMatch.houseNumbersCorrespond(requested, returned)).isFalse();
    }

    @Test
    void aNonNumericHouseNumberFallsBackToExactComparison() {
        assertThat(GoogleAddressMatch.houseNumbersCorrespond("בית הדר", "בית הדר")).isTrue();
        assertThat(GoogleAddressMatch.houseNumbersCorrespond("בית הדר", "בית שלום")).isFalse();
    }
}
