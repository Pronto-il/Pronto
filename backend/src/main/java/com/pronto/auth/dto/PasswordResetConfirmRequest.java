package com.pronto.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code POST /api/auth/password-reset/confirm}. {@link OtpSubmissionRequest}'s shape plus the new
 * password.
 *
 * <p>{@code @Size(min = 8)} is the same policy {@code RegisterRequest} applies, stated here rather
 * than inherited so the two cannot drift — a reset endpoint that accepted weaker passwords than
 * registration would be the obvious way to get a weak password onto an account.
 */
public record PasswordResetConfirmRequest(
        @NotNull UUID challengeId,
        @NotNull @Pattern(regexp = "\\d{6}", message = "must be a 6-digit code") String code,
        @NotBlank @Size(min = 8) String newPassword
) {
}
