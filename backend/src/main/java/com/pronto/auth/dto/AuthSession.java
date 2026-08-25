package com.pronto.auth.dto;

/**
 * An issued session. Structurally identical to the {@code LoginResponse} this replaced, so the
 * client's token handling is unchanged — what changed is which endpoints can produce it.
 *
 * <p><b>Exactly two places construct this</b>, and both sit strictly behind a redeemed one-time
 * password: {@code POST /api/auth/login/otp} and {@code POST /api/auth/verify-phone} (registration
 * completion, where the user has just proved both contact channels in sequence). No other auth
 * endpoint returns a token. That is the structural form of MS1's central rule — a password alone
 * never yields a session — and it is worth stating here because "which response types can carry a
 * token" is the cheapest possible audit of it.
 */
public record AuthSession(String token, String tokenType, long expiresIn, UserSummary user) {
}
