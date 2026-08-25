package com.pronto.maps;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address normalisation and change-detection behind "do not geocode an unchanged address
 * repeatedly" and "an address edit invalidates its coordinates".
 *
 * <p>Both of those are cost and correctness properties, and both rest entirely on
 * {@link PostalAddress#contentHash()} being stable for the same address and different for a
 * different one. The tests below pin exactly where that line falls.
 */
class PostalAddressTest {

    @Test
    void identicalAddressesHashIdentically() {
        assertThat(new PostalAddress("תל אביב", "דיזנגוף", "10").contentHash())
                .isEqualTo(new PostalAddress("תל אביב", "דיזנגוף", "10").contentHash());
    }

    /** Cosmetic differences are not address changes and must not trigger a paid re-geocode. */
    @Test
    void whitespaceAndCaseDifferencesAreNotAddressChanges() {
        String canonical = new PostalAddress("תל אביב", "Dizengoff", "10").contentHash();

        assertThat(new PostalAddress("  תל אביב  ", "dizengoff", " 10 ").contentHash()).isEqualTo(canonical);
        assertThat(new PostalAddress("תל  אביב", "DIZENGOFF", "10").contentHash()).isEqualTo(canonical);
    }

    @Test
    void aDifferentHouseNumberIsADifferentAddress() {
        assertThat(new PostalAddress("תל אביב", "דיזנגוף", "10").contentHash())
                .isNotEqualTo(new PostalAddress("תל אביב", "דיזנגוף", "12").contentHash());
    }

    @Test
    void aDifferentCityIsADifferentAddress() {
        assertThat(new PostalAddress("תל אביב", "הרצל", "1").contentHash())
                .isNotEqualTo(new PostalAddress("חיפה", "הרצל", "1").contentHash());
    }

    /**
     * The three fields are hashed in a way that cannot be confused by concatenation: 'ab' + 'c'
     * and 'a' + 'bc' must not collide, or two genuinely different addresses would share
     * coordinates.
     */
    @Test
    void fieldBoundariesAreNotAmbiguous() {
        assertThat(new PostalAddress("ab", "c", "1").contentHash())
                .isNotEqualTo(new PostalAddress("a", "bc", "1").contentHash());
    }

    @Test
    void blankFieldsAreTreatedAsAbsent() {
        assertThat(new PostalAddress("  ", "", null).city()).isNull();
        assertThat(new PostalAddress("תל אביב", "   ", "10").street()).isNull();
    }

    // ---- geocodability ----

    /**
     * A city with no street resolves to a locality centroid, which is precisely the fake precision
     * MS2 exists to remove. Refused before any provider is asked.
     */
    @Test
    void aCityWithNoStreetIsNotGeocodable() {
        assertThat(new PostalAddress("תל אביב", null, null).isGeocodable()).isFalse();
    }

    @Test
    void aStreetWithNoCityIsNotGeocodable() {
        assertThat(new PostalAddress(null, "דיזנגוף", "10").isGeocodable()).isFalse();
    }

    /**
     * A missing house number is tolerated: some Israeli addresses genuinely have none, and a
     * street-level fix is still a real fix rather than an invented one.
     */
    @Test
    void aCityAndStreetWithNoHouseNumberIsGeocodable() {
        assertThat(new PostalAddress("תל אביב", "דיזנגוף", null).isGeocodable()).isTrue();
    }

    // ---- query construction ----

    @Test
    void queryIsWrittenTheWayAnIsraeliAddressIsWrittenAndNamesTheCountry() {
        assertThat(new PostalAddress("תל אביב", "דיזנגוף", "10").toQuery())
                .isEqualTo("דיזנגוף 10, תל אביב, ישראל");
    }

    /**
     * The country is appended rather than left to the provider's region bias, which is only a
     * preference: a street name that also exists elsewhere must not resolve to another country.
     */
    @Test
    void everyQueryNamesTheCountryExplicitly() {
        assertThat(new PostalAddress("חיפה", "הרצל", null).toQuery()).endsWith(", ישראל");
    }

    @Test
    void anEmptyAddressProducesAnEmptyQueryRatherThanABareCountry() {
        assertThat(new PostalAddress(null, null, null).toQuery()).isEmpty();
    }
}
