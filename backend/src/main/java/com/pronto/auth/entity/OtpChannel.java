package com.pronto.auth.entity;

/**
 * How an OTP reaches its recipient. Not persisted — it is a function of {@link OtpPurpose}, so
 * storing it as well would create two sources of truth for one fact. Surfaced to the client on
 * every challenge response so the UI can say "check your email" or "check your messages" without
 * inferring it from the purpose.
 */
public enum OtpChannel {
    EMAIL,
    SMS
}
