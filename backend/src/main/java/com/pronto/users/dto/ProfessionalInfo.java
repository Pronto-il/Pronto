package com.pronto.users.dto;

import java.math.BigDecimal;

/**
 * The nested {@code professional} object in {@code GET /api/users/me}'s response for a
 * {@code PROFESSIONAL}-role caller. See {@code docs/architecture/api-contract.md} §2.4.
 *
 * <p>{@code profileImageUrl} — added MS10 profile redesign §6, so a professional's own
 * photo can be shown read-only on the shared `/profile` page. Resolved the same way
 * {@code professionals.dto.ProfessionalProfileResponse.profileImageUrl} already is: {@code
 * null} when {@code professionals.profile_image_key} is {@code null}, otherwise a
 * presigned URL via {@code storage.service.StorageService#getPresignedUrl}.
 */
public record ProfessionalInfo(Long categoryId, String serviceArea, BigDecimal basePrice, String profileImageUrl) {
}
