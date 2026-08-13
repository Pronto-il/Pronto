package com.pronto.storage.dto;

/**
 * Response body for {@code POST /api/storage/images}. See
 * {@code docs/architecture/api-contract-issues.md} §2.3.
 */
public record ImageUploadResponse(String imageKey, String imageUrl, String contentType, long sizeBytes) {
}
