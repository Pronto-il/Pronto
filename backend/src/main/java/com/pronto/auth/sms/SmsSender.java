package com.pronto.auth.sms;

import com.pronto.auth.entity.OtpPurpose;

/**
 * Outbound SMS. New in Production MS1 — before it, this system had no SMS capability at all
 * ({@code notifications.entity.NotificationChannel} is explicitly {@code IN_APP}/{@code EMAIL}
 * only), so a phone number could be collected but never proved.
 *
 * <p>Deliberately the same shape as {@code auth.email.EmailSender}: a transport selected by
 * configuration ({@code pronto.sms.mode}), with the provider's SDK confined entirely to the
 * implementation. {@code AuthService} knows that a code needs to reach a phone; it does not know
 * that AWS exists.
 */
public interface SmsSender {

    /**
     * Delivers a one-time password by SMS.
     *
     * @param toPhoneE164 recipient in canonical E.164 ({@code +972501234567}) — the only format
     *                    accepted, because it is the only one that is unambiguous to a carrier
     * @param purpose     decides the copy; always an {@code SMS}-channel purpose
     * @param code        the six plaintext digits, to be handed to the transport and then forgotten
     * @throws com.pronto.common.exception.ApiException {@code OTP_DELIVERY_FAILED} if the provider
     *                                                 rejected or could not accept the message
     */
    void sendOtp(String toPhoneE164, OtpPurpose purpose, String code);
}
