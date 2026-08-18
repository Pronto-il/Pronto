package com.pronto.storage.dto;

/**
 * One entry in {@code POST /api/storage/images/presigned-urls}'s response {@code images}
 * array. See {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §12.2.
 */
public record PresignedImageUrlEntry(String imageKey, String imageUrl) {
}
