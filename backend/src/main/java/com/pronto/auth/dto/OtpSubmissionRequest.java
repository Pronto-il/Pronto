package com.pronto.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * "Here is the code you sent me." The request body for {@code POST /api/auth/verify-email},
 * {@code /verify-phone}, {@code /login/otp} and {@code /password-reset/confirm} (which extends it
 * with a new password) — one shape, because redeeming an OTP is one operation regardless of what it
 * unlocks.
 *
 * <p>The identifier is deliberately absent. Earlier versions of these endpoints took
 * {@code (email, code)}, which meant every OTP request restated an account identifier and every
 * error response was a potential answer to "does this account exist". A challenge id is a handle to
 * a conversation the server started; it identifies the account without the caller naming it.
 *
 * <p>{@code @Pattern} rejects anything that is not six digits before any database work happens.
 * That is input hygiene, not a security control — it saves a lookup and a hash on obvious junk, and
 * it deliberately does not distinguish "wrong shape" from "wrong code" in any way a caller can
 * observe beyond the standard validation envelope.
 */
public record OtpSubmissionRequest(
        @NotNull UUID challengeId,
        @NotNull @Pattern(regexp = "\\d{6}", message = "must be a 6-digit code") String code
) {
}
