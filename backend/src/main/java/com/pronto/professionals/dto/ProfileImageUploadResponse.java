package com.pronto.professionals.dto;

/**
 * Response body for {@code POST /api/professionals/me/profile-image}. Mirrors the shape of
 * {@code storage.dto.ImageUploadResponse}, scoped to this endpoint's own key/URL (a
 * professional's {@code profile_image_key}, not an issue-image temp key).
 */
public record ProfileImageUploadResponse(String imageKey, String imageUrl, String contentType, long sizeBytes) {
}
