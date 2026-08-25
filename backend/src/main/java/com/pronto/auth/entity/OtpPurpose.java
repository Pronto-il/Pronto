package com.pronto.auth.entity;

import java.time.Duration;

/**
 * What an issued one-time password is for, and everything that follows from that: which channel
 * carries it and how long it lives.
 *
 * <p>Mirrors {@code ck_verification_codes_purpose} (V47) exactly — the constant names are the
 * values stored in {@code verification_codes.purpose}, so adding one here without extending that
 * CHECK produces an immediate insert failure rather than a silent divergence.
 *
 * <p><b>Two TTLs, deliberately.</b> A login OTP lives 10 minutes and a contact-verification OTP 15.
 * The asymmetry is not cosmetic: a login code is redeemed within seconds by a user who is sitting
 * at the screen that requested it, so a shorter window costs nothing and shrinks the interception
 * surface; a registration code has to survive an email arriving in a spam folder or an SMS being
 * read on a second device, where 10 minutes produces real, avoidable failures. {@code
 * PASSWORD_RESET} follows the verification window for the same reason.
 */
public enum OtpPurpose {

    /** Registration step 1: proves the registrant controls the email address they claimed. */
    EMAIL_VERIFICATION(OtpChannel.EMAIL, Duration.ofMinutes(15)),

    /**
     * Registration step 2, and the legacy-account phone-capture flow: proves control of the phone
     * number. Redeeming this is what completes registration and issues the first JWT.
     */
    PHONE_VERIFICATION(OtpChannel.SMS, Duration.ofMinutes(15)),

    /** Second factor for a login that presented an email address as its identifier. */
    EMAIL_LOGIN_OTP(OtpChannel.EMAIL, Duration.ofMinutes(10)),

    /** Second factor for a login that presented a phone number as its identifier. */
    PHONE_LOGIN_OTP(OtpChannel.SMS, Duration.ofMinutes(10)),

    /**
     * Password recovery. Always email, never SMS, even for an account that logs in by phone: the
     * verified email address is the channel this platform can prove was confirmed for every
     * account, including every legacy row.
     */
    PASSWORD_RESET(OtpChannel.EMAIL, Duration.ofMinutes(15));

    private final OtpChannel channel;
    private final Duration timeToLive;

    OtpPurpose(OtpChannel channel, Duration timeToLive) {
        this.channel = channel;
        this.timeToLive = timeToLive;
    }

    public OtpChannel channel() {
        return channel;
    }

    public Duration timeToLive() {
        return timeToLive;
    }

    /** True for the two steps that complete a registration (and so may end in a JWT). */
    public boolean isContactVerification() {
        return this == EMAIL_VERIFICATION || this == PHONE_VERIFICATION;
    }

    /** True for the two second-factor purposes a {@code POST /api/auth/login} can produce. */
    public boolean isLoginOtp() {
        return this == EMAIL_LOGIN_OTP || this == PHONE_LOGIN_OTP;
    }
}
