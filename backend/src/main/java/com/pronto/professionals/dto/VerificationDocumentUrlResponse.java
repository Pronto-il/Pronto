package com.pronto.professionals.dto;

/**
 * {@code GET /api/admin/professionals/{professionalId}/verification-document}. MS1 (D-F).
 *
 * <p><b>{@link #url} is a bearer capability.</b> Anyone holding it can fetch a private compliance
 * document, without authenticating, until it expires — which is why it is minted on demand rather
 * than embedded in the review-detail response, why it is short-lived
 * ({@code pronto.storage.presigned-url-ttl-seconds}, 300 by default), and why neither it nor the
 * underlying object key is ever written to a log. The object key is deliberately not a field
 * here: an operator has no use for it, and it is the durable half of the secret.
 *
 * @param expiresInSeconds how long the URL remains valid, so a review UI can re-request rather
 *                         than hold one open indefinitely
 */
public record VerificationDocumentUrlResponse(
        Long professionalId,
        String url,
        long expiresInSeconds
) {
}
