package com.pronto.auth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configuration contract of {@link AuthOtpPolicy}.
 *
 * <p>The malformed-value cases matter more than the happy path. This is a property whose
 * {@code false} value removes an authentication step, so the failure mode worth testing is not "it
 * read the wrong value" but "it read an unreadable value and picked one anyway".
 */
class AuthOtpPolicyTest {

    @ParameterizedTest(name = "\"{0}\" enables the OTP requirement")
    @ValueSource(strings = {"true", "TRUE", "True", "  true  "})
    void trueInAnyCasingRequiresOtp(String raw) {
        assertThat(new AuthOtpPolicy(OtpPolicies.enabled(), raw).isOtpRequired()).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" disables the OTP requirement")
    @ValueSource(strings = {"false", "FALSE", "False", "  false  "})
    void falseInAnyCasingDisablesOtp(String raw) {
        assertThat(new AuthOtpPolicy(OtpPolicies.enabled(), raw).isOtpRequired()).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\" refuses to start rather than guessing")
    @ValueSource(strings = {"", "  ", "yes", "no", "on", "off", "1", "0", "flase", "TRUE!", "null"})
    void anythingElseRefusesToStart(String raw) {
        // Note that "yes"/"no"/"on"/"off"/"1"/"0" are all accepted by Spring's own relaxed boolean
        // binding and are all rejected here, deliberately: for this property the set of spellings
        // that mean "off" should be exactly one.
        assertThatThrownBy(() -> new AuthOtpPolicy(OtpPolicies.enabled(), raw))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_OTP_REQUIRED")
                .hasMessageContaining("Expected exactly 'true' or 'false'");
    }

    @Test
    void nullRefusesToStart() {
        assertThatThrownBy(() -> new AuthOtpPolicy(OtpPolicies.enabled(), null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void announcingDoesNotThrowInEitherMode() {
        // @PostConstruct runs inside bean initialization; an exception here would fail the boot for
        // a reason that has nothing to do with the configuration being wrong.
        new AuthOtpPolicy(OtpPolicies.enabled(), "true").announce();
        new AuthOtpPolicy(OtpPolicies.enabled(), "false").announce();
    }
}
