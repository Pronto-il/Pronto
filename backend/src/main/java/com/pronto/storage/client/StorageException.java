package com.pronto.storage.client;

/**
 * Thrown by a {@link StorageClient} implementation when the underlying storage operation
 * itself fails (disk I/O error in {@code local} mode, an AWS SDK error in {@code s3} mode).
 * Callers (services in {@code storage}/{@code ai}/{@code issues}) catch this and translate
 * it into {@code 502 STORAGE_SERVICE_ERROR} via {@code common.exception.ApiException} — see
 * {@code docs/architecture/api-contract-issues.md} §1.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
