package com.pronto.storage.client;

import java.time.Duration;

/**
 * Storage abstraction behind which {@code local}/{@code s3} implementations are swapped via
 * {@code pronto.storage.mode} — mirrors the {@code auth.email.EmailSender} mock/real split
 * from Milestone 1. See {@code docs/architecture/api-contract-issues.md} §3.2.
 *
 * <p><b>Deviation from the contract doc's exact interface snippet, flagged.</b> §3.2 shows
 * only {@code upload}/{@code download}/{@code exists}. A fourth method, {@link #presignUrl},
 * was added — originally as {@code resolveUrl} (a deterministic, non-expiring URL), replaced
 * by this time-limited presigned-URL form in backend MS9
 * ({@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §1) to fix a
 * {@code net::ERR_BLOCKED_BY_ORB} bug: a plain {@code <img src>} cannot carry the
 * {@code Authorization} header the old JWT-gated retrieval route required. {@code resolveUrl}
 * was removed rather than deprecated — see that design doc §1 for the full reasoning (keeping
 * a second, differently-behaved URL-producing method around risked a future call site silently
 * reintroducing the ORB bug or a permanent-URL leak).
 */
public interface StorageClient {

    /**
     * Stores {@code content} under {@code key}, overwriting any existing object at that key.
     *
     * @throws StorageException on a genuine storage I/O failure (disk error / S3 SDK error).
     */
    StoredObject upload(String key, byte[] content, String contentType);

    /**
     * @throws StorageException if the object doesn't exist, or on a genuine storage I/O
     *                           failure. Callers are expected to check {@link #exists} first
     *                           when "not found" needs to be distinguished from "storage is
     *                           broken."
     */
    byte[] download(String key);

    /**
     * @throws StorageException on a genuine storage I/O failure (never for "not found" —
     *                           that's a {@code false} return, not an exception).
     */
    boolean exists(String key);

    /**
     * Returns a URL from which {@code key} can be fetched directly — no Authorization header,
     * no cookie, nothing but the URL itself — valid for {@code expiry} from the moment this
     * method returns. Performs NO authorization of its own; the caller ({@code StorageService})
     * is responsible for deciding whether the current caller should be allowed to see this key
     * BEFORE calling this method. Local mode: an HMAC-signed query-string URL back to this
     * backend's own {@code GET /api/storage/images/**}. S3 mode: a real AWS S3 presigned GET
     * URL, pointing directly at S3, never touching this backend. See
     * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §1/§3/§5.
     */
    String presignUrl(String key, Duration expiry);
}
