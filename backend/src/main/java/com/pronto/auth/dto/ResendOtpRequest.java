package com.pronto.auth.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /api/auth/otp/resend}. Replaces the challenge identified by {@link #challengeId} with
 * a freshly generated code of the same purpose, sent to the same channel.
 *
 * <p>One resend endpoint for all five OTP purposes, rather than one per flow: the purpose is
 * already recorded on the challenge, so asking the client to restate it would only create a way for
 * the client to get it wrong. Rate limiting ({@value com.pronto.auth.service.OtpService#RESEND_COOLDOWN_SECONDS}s
 * cooldown, {@value com.pronto.auth.service.OtpService#MAX_ISSUES_PER_HOUR} per purpose per hour)
 * lives on the service, not here.
 */
public record ResendOtpRequest(@NotNull UUID challengeId) {
}
