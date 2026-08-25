package com.pronto.auth.dto;

/**
 * The single response shape for every step of registration and login.
 *
 * <p>{@code POST /api/auth/register}, {@code /verify-email}, {@code /verify-phone}, {@code /login}
 * and {@code /login/otp} all return this. One shape rather than five, because the client's logic is
 * the same in all five cases — read {@link #nextStep}, then either render the challenge or store
 * the session — and because five near-identical records is how "login returns a token but
 * verify-phone returns a slightly different token wrapper" gets introduced by accident.
 *
 * <p>Exactly one of {@link #challenge}/{@link #session} is populated:
 * {@link AuthNextStep#AUTHENTICATED} carries a session, {@link AuthNextStep#LOGIN} carries neither,
 * and every other step carries a challenge.
 *
 * @param emailVerified current state of the account's email channel, so the client can render
 *                      progress without a second round trip
 * @param phoneVerified current state of the account's phone channel
 */
public record AuthStepResponse(AuthNextStep nextStep, OtpChallengeResponse challenge, AuthSession session,
                                boolean emailVerified, boolean phoneVerified) {

    public static AuthStepResponse challenge(AuthNextStep nextStep, OtpChallengeResponse challenge,
                                              boolean emailVerified, boolean phoneVerified) {
        return new AuthStepResponse(nextStep, challenge, null, emailVerified, phoneVerified);
    }

    public static AuthStepResponse authenticated(AuthSession session, boolean emailVerified,
                                                  boolean phoneVerified) {
        return new AuthStepResponse(AuthNextStep.AUTHENTICATED, null, session, emailVerified, phoneVerified);
    }

    public static AuthStepResponse goToLogin(boolean emailVerified, boolean phoneVerified) {
        return new AuthStepResponse(AuthNextStep.LOGIN, null, null, emailVerified, phoneVerified);
    }
}
