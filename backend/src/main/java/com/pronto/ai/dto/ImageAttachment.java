package com.pronto.ai.dto;

import java.util.Base64;

/**
 * An issue photo resolved and encoded once, ready to hand to an
 * {@code client.AiClassificationClient}.
 *
 * <p>Never a remote URL — see {@code docs/architecture/api-contract-issues.md} §3.1's
 * "image reachability" decision: {@code pronto.storage.mode=local} URLs point at
 * {@code localhost}, which a real OpenAI call could never reach. The bytes travel inline.
 *
 * <p><b>Holds the finished data URI rather than the raw bytes.</b> Base64 encoding used to
 * happen in the OpenAI client, i.e. once per use of an attachment; doing it here means an
 * attachment is encoded exactly once no matter how many model calls consume it, and only one
 * copy of the payload is held rather than the raw bytes plus an encoded copy. {@link #byteCount}
 * keeps the original size available for logging, since the encoded length is not it.
 *
 * <p>{@link #dataUri} is never logged — only {@link #key} and {@link #byteCount} are.
 */
public record ImageAttachment(String key, String contentType, String dataUri, int byteCount) {

    /** Encodes {@code content} once. {@code null}/empty content yields a blank data URI. */
    public static ImageAttachment of(String key, byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            return new ImageAttachment(key, contentType, "", 0);
        }
        String dataUri = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(content);
        return new ImageAttachment(key, contentType, dataUri, content.length);
    }

    /** False when the source object was missing or empty — such an attachment is not sent. */
    public boolean hasContent() {
        return dataUri != null && !dataUri.isBlank() && byteCount > 0;
    }
}
