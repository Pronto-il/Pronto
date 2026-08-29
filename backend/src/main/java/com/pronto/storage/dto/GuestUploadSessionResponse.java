package com.pronto.storage.dto;

/**
 * Response shape for {@code POST /api/storage/guest-sessions}.
 *
 * <p>{@code guestSessionToken} is opaque to the client — the guest id inside it is deliberately
 * not echoed as a separate field, so nothing in the frontend can be tempted to send an id instead
 * of the signed token it was minted with. {@code expiresInSeconds} exists so the client can drop a
 * dead session and mint a new one rather than discovering it expired on an upload, the same reason
 * {@code professionals.dto.VerificationDocumentUrlResponse} reports its own TTL.
 */
public record GuestUploadSessionResponse(String guestSessionToken, long expiresInSeconds) {
}
