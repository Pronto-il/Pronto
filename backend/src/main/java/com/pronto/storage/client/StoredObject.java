package com.pronto.storage.client;

/**
 * Result of a successful {@link StorageClient#upload}. See
 * {@code docs/architecture/api-contract-issues.md} §3.2.
 *
 * @param key         the object key it was stored under (unchanged from what was passed in)
 * @param url         the resolved, caller-fetchable URL for this object (local retrieval
 *                    endpoint in {@code local} mode, the S3 object URL in {@code s3} mode)
 * @param contentType the stored content-type
 * @param sizeBytes   size of the stored content in bytes
 */
public record StoredObject(String key, String url, String contentType, long sizeBytes) {
}
