package com.pronto.storage.dto;

/**
 * Result of {@code StorageService.retrieve} — raw bytes plus the content-type to stream
 * back for {@code GET /api/storage/images/**} (§2.4).
 */
public record RetrievedImage(byte[] content, String contentType) {
}
