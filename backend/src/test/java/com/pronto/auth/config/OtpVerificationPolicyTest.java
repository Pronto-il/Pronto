package com.pronto.auth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configuration contract of {@link OtpVerificationPolicy}, and the gating it performs over
 * {@link VerificationPolicy} and {@link AuthOtpPolicy}.
 *
 * <p>Two things are being pinned. The parsing cases matter for the reason
 * {@link AuthOtpPolicyTest}'s do — this is the property that decides whether the platform verifies
 * anybody, so the failure worth testing is "it read an unreadable value and picked one anyway". The
 * gating cases matter because the master switch's whole value is that an operator sets <em>one</em>
 * variable: a gate wired to two of the three sub-policies would produce a deployment that
 * registers without verifying and then demands a login code nobody can be sent.
 */
class OtpVerificationPolicyTest {

    @ParameterizedTest(name = "\"{0}\" enables OTP verification")
    @ValueSource(strings = {"true", "TRUE", "True", "  true  "})
    void trueInAnyCasingEnablesVerification(String raw) {
        assertThat(new OtpVerificationPolicy(raw).isOtpVerificationEnabled()).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" disables OTP verification")
    @ValueSource(strings = {"false", "FALSE", "False", "  false  "})
    void falseInAnyCasingDisablesVerification(String raw) {
        assertThat(new OtpVerificationPolicy(raw).isOtpVerificationEnabled()).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\" refuses to start rather than guessing")
    @ValueSource(strings = {"", "  ", "yes", "no", "on", "off", "1", "0", "flase", "disabled"})
    void anythingElseRefusesToStart(String raw) {
        assertThatThrownBy(() -> new OtpVerificationPolicy(raw))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OTP_VERIFICATION_ENABLED")
                .hasMessageContaining("Expected exactly 'true' or 'false'");
    }

    @Test
    void nullRefusesToStart() {
        assertThatThrownBy(() -> new OtpVerificationPolicy(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void announcingDoesNotThrowInEitherMode() {
        // @PostConstruct runs inside bean initialization; throwing here would fail the boot for a
        // reason unrelated to the configuration being wrong.
        OtpPolicies.enabled().announce();
        OtpPolicies.disabled().announce();
    }

    // ---- the gating, which is the reason this class exists at all ------------------------------

    @ParameterizedTest(name = "sms={0} email={1} are both forced off by the master switch")
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void disablingTheMasterForcesBothVerificationChannelsOff(boolean sms, boolean email) {
        VerificationPolicy policy = new VerificationPolicy(OtpPolicies.disabled(), sms, email);

        assertThat(policy.isSmsVerificationRequired()).isFalse();
        assertThat(policy.isEmailVerificationRequired()).isFalse();
    }

    @ParameterizedTest(name = "otp-required={0} is forced off by the master switch")
    @ValueSource(strings = {"true", "false"})
    void disablingTheMasterForcesTheLoginSecondFactorOff(String otpRequired) {
        assertThat(new AuthOtpPolicy(OtpPolicies.disabled(), otpRequired).isOtpRequired()).isFalse();
    }

    @ParameterizedTest(name = "sms={0} email={1} survive the master switch being on")
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void enablingTheMasterLeavesTheFineGrainedFlagsInCharge(boolean sms, boolean email) {
        // The master gates; it does not override upward. An operator who turned one channel off for
        // its own provider reason keeps that setting when the master is on.
        VerificationPolicy policy = new VerificationPolicy(OtpPolicies.enabled(), sms, email);

        assertThat(policy.isSmsVerificationRequired()).isEqualTo(sms);
        assertThat(policy.isEmailVerificationRequired()).isEqualTo(email);
    }

    @Test
    void theMasterDefaultsToEnabled() {
        // The bypass must be reachable only by someone who typed the variable and meant it. A
        // deployment that sets nothing verifies exactly as it always did.
        assertThat(new OtpVerificationPolicy("true").isOtpVerificationEnabled()).isTrue();
        assertThat(new VerificationPolicy(OtpPolicies.enabled(), true, true)
                .isEmailVerificationRequired()).isTrue();
        assertThat(new AuthOtpPolicy(OtpPolicies.enabled(), "true").isOtpRequired()).isTrue();
    }

    @Test
    void aMalformedSubFlagStillRefusesToStartWhileTheMasterIsOff() {
        // The master short-circuits the REQUIREMENT, not the parsing. A deployment carrying
        // AUTH_OTP_REQUIRED=flase has a broken config, and silently accepting it because OTP
        // happens to be off would hide the typo until the day verification is turned back on.
        assertThatThrownBy(() -> new AuthOtpPolicy(OtpPolicies.disabled(), "flase"))
                .isInstanceOf(IllegalStateException.class);
    }
}
