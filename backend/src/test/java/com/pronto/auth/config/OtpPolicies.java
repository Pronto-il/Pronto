package com.pronto.auth.config;

/**
 * Test fixtures for the OTP master switch.
 *
 * <p>{@link OtpVerificationPolicy} gates {@link VerificationPolicy} and {@link AuthOtpPolicy}, so
 * every test that builds either of those has to say which world it is in. Naming the two worlds
 * here keeps that decision readable at each call site — {@code OtpPolicies.enabled()} rather than a
 * bare {@code new OtpVerificationPolicy("true")} whose string argument means nothing at a glance —
 * and gives the suite one place to change if the master flag ever grows a third state.
 */
public final class OtpPolicies {

    /** The default and the intended long-term rule: this deployment issues one-time passwords. */
    public static OtpVerificationPolicy enabled() {
        return new OtpVerificationPolicy("true");
    }

    /** The current feedback/beta phase: {@code OTP_VERIFICATION_ENABLED=false}. */
    public static OtpVerificationPolicy disabled() {
        return new OtpVerificationPolicy("false");
    }

    private OtpPolicies() {
    }
}
