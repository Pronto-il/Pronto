package com.pronto.auth.email;

import com.pronto.auth.entity.OtpPurpose;

/**
 * Outbound email, abstracted so the transport (log in development, Amazon SES in Production) is a
 * configuration decision rather than a code path {@code AuthService} knows about. Selection is
 * driven by {@code pronto.email.mode} — see {@link LoggingEmailSender} and {@code SesEmailSender}.
 *
 * <p><b>Production MS1 replaced {@code sendVerificationCode}</b> with {@link #sendOtp}. The old
 * signature assumed a single kind of code (registration email verification); there are now three
 * distinct email OTPs — verification, login second factor, password reset — that must not share a
 * subject line, because "here is your login code" arriving when you did not try to log in is the
 * single most useful warning this system can give a user whose password has leaked.
 */
public interface EmailSender {

    /**
     * Delivers a one-time password.
     *
     * @param toEmail canonical recipient address
     * @param purpose decides the subject/body copy; always an {@code EMAIL}-channel purpose
     * @param code    the six plaintext digits — to be handed to the transport and then forgotten.
     *                Implementations must never log this value outside a {@code local} environment.
     * @throws com.pronto.common.exception.ApiException {@code OTP_DELIVERY_FAILED} if the provider
     *                                                 rejected or could not accept the message
     */
    void sendOtp(String toEmail, OtpPurpose purpose, String code);

    /**
     * Order-status-change email, added in Milestone 5. See
     * {@code docs/architecture/api-contract-notifications.md} §4.4 — same interface, reused rather
     * than superseded, per that section's reasoning.
     */
    void sendOrderStatusEmail(String toEmail, String subject, String bodyText);
}
