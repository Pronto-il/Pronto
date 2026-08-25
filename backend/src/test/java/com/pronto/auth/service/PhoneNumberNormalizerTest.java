package com.pronto.auth.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Canonical phone identity: three spellings in, one stored value out. */
class PhoneNumberNormalizerTest {

    private final PhoneNumberNormalizer normalizer = new PhoneNumberNormalizer("IL");

    @ParameterizedTest
    @ValueSource(strings = {
            "0502234567",       // local, bare
            "050-223-4567",     // local, hyphenated as an Israeli would write it
            "050 223 4567",     // local, spaced
            "(050) 223-4567",   // local, parenthesised
            "+972502234567",    // international
            "+972 50 223 4567", // international, spaced
            "00972502234567",   // ISDN international prefix
            " 0502234567 ",     // surrounding whitespace
    })
    void everyAcceptedSpelling_normalizesToTheOneCanonicalValue(String input) {
        assertThat(normalizer.normalize(input, "phone")).isEqualTo("+972502234567");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "not-a-number",
            "12345",              // too short to be anything
            "050223456789012",    // too long
            "0502234567abc",
            "+9725022",           // truncated
    })
    void anythingThatIsNotARealNumber_isRejectedAsAFieldError(String input) {
        assertThatThrownBy(() -> normalizer.normalize(input, "phone"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat((List<FieldError>) api.getDetails())
                            .extracting(FieldError::field).containsExactly("phone");
                });
    }

    @Test
    void nullIsRejected() {
        assertThatThrownBy(() -> normalizer.normalize(null, "phone")).isInstanceOf(ApiException.class);
    }

    @Test
    void anIsraeliLandline_isRejected_becauseItCannotReceiveAnSms() {
        // 03-xxxxxxx is a Tel Aviv fixed line. Accepting it at registration would create an account
        // that can never finish verifying, and the failure would surface as a silent non-delivery
        // rather than on the field the user is looking at.
        assertThatThrownBy(() -> normalizer.normalize("03-5551234", "phone"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void anUnallocatedMobileRange_isRejected() {
        // The reason this class uses libphonenumber rather than a regex: 050-1xxxxxx is not an
        // assigned Israeli mobile range, and a "^05\\d{8}$" pattern would happily accept it.
        assertThatThrownBy(() -> normalizer.normalize("0501234567", "phone"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aForeignNumberInFullInternationalForm_isAccepted() {
        // The default region only decides how to read a LOCAL spelling. A number that names its own
        // country code is unaffected by it — Pronto is Israel-first, not Israel-only.
        assertThat(normalizer.normalize("+447911123456", "phone")).isEqualTo("+447911123456");
    }

    @Test
    void tryNormalize_returnsEmptyInsteadOfThrowing_forIdentifierResolution() {
        // Login takes one `identifier` field. "This is not a phone number" must mean "try it as an
        // email address", not "400".
        assertThat(normalizer.tryNormalize("customer@example.com")).isEmpty();
        assertThat(normalizer.tryNormalize("0502234567")).contains("+972502234567");
    }

    @Test
    void mask_showsEnoughToRecogniseAHandsetAndNoMore() {
        String masked = PhoneNumberNormalizer.mask("+972502234567");

        assertThat(masked).startsWith("+9725").endsWith("67").doesNotContain("2234");
        assertThat(masked).hasSameSizeAs("+972502234567");
    }

    @Test
    void mask_failsClosedOnAnythingUnexpectedlyShort() {
        assertThat(PhoneNumberNormalizer.mask(null)).isEqualTo("***");
        assertThat(PhoneNumberNormalizer.mask("+9725")).isEqualTo("***");
    }

    @Test
    void aDifferentDefaultRegion_readsLocalSpellingsDifferently() {
        assertThat(new PhoneNumberNormalizer("GB").normalize("07911 123456", "phone"))
                .isEqualTo("+447911123456");
    }
}
