package com.pronto.auth.dto;

import com.pronto.auth.entity.OtpChannel;

import java.util.UUID;

/**
 * An issued OTP challenge, as much of it as a client is allowed to know.
 *
 * <p>Note what is not here: the code, and the unmasked destination. The client needs to render "we
 * sent a code to {@code d***@example.com}, it expires in 15 minutes" and then post the digits back
 * against {@link #challengeId} — nothing in that requires either secret.
 *
 * @param challengeId       opaque handle; the only thing the client ever sends back. Unguessable,
 *                          and only ever handed to a caller who already proved something, which is
 *                          what keeps these flows from becoming an account-existence oracle
 * @param channel           where the code went, so the UI can say "check your email" or "check your
 *                          messages"
 * @param destinationMasked partially-masked address or number — enough for a user to recognize
 *                          their own, not enough to read one off a screen they should not have
 *                          reached
 * @param expiresInSeconds  lifetime from issuance; drives the client's countdown
 * @param delivered         whether the provider accepted the message. {@code false} means the
 *                          challenge is live but nothing arrived, and the UI should lead with
 *                          "resend" rather than with a code entry field. Always reported as
 *                          {@code true} by {@code POST /api/auth/password-reset/request}, which
 *                          must answer identically for accounts that exist and accounts that do not
 */
public record OtpChallengeResponse(UUID challengeId, OtpChannel channel, String destinationMasked,
                                    long expiresInSeconds, boolean delivered) {
}
