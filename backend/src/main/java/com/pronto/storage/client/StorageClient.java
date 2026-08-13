package com.pronto.storage.client;

/**
 * Storage abstraction behind which {@code local}/{@code s3} implementations are swapped via
 * {@code pronto.storage.mode} — mirrors the {@code auth.email.EmailSender} mock/real split
 * from Milestone 1. See {@code docs/architecture/api-contract-issues.md} §3.2.
 *
 * <p><b>Deviation from the contract doc's exact interface snippet, flagged.</b> §3.2 shows
 * only {@code upload}/{@code download}/{@code exists}. A fourth method, {@link #resolveUrl},
 * was added because §2.2 step 6 requires re-deriving the <em>same</em> URL an already-
 * uploaded key originally resolved to — at issue-creation time, only the key is known (no
 * {@code issue_images} row, and therefore no stored URL, exists yet at upload time; see §3.3).
 * Both implementations compute the URL deterministically from the key alone, so
 * {@code resolveUrl} needs no new state — {@link #upload} itself is implemented in terms of
 * it. Not calling {@link #upload} again was the alternative rejected: it would re-write the
 * object's bytes for no reason and require {@code issues} to hold onto raw image bytes
 * across the classify→confirm gap, which it never does.
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

    /** Deterministic, side-effect-free: does not verify the object actually exists. */
    String resolveUrl(String key);
}
