package com.pronto.users.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The nested {@code professional} object in {@code GET /api/users/me}'s response for a
 * {@code PROFESSIONAL}-role caller. See {@code docs/architecture/api-contract.md} §2.4.
 *
 * <p>{@code profileImageUrl} — added MS10 profile redesign §6, so a professional's own
 * photo can be shown read-only on the shared `/profile` page. Resolved the same way
 * {@code professionals.dto.ProfessionalProfileResponse.profileImageUrl} already is: {@code
 * null} when {@code professionals.profile_image_key} is {@code null}, otherwise a
 * presigned URL via {@code storage.service.StorageService#getPresignedUrl}.
 *
 * <p><b>MS4:</b> {@code categoryId} became {@code categoryIds} (a professional may serve
 * several trades) and free-text {@code serviceArea} became {@code serviceRegion}, the Hebrew
 * label of the canonical {@code service_regions} row — {@code null} for a pre-MS4 professional
 * whose old free text named no recognisable region, which the shared profile page renders as
 * "not set" rather than guessing.
 */
public record ProfessionalInfo(List<Long> categoryIds, String serviceRegion, BigDecimal basePrice,
                                String profileImageUrl) {
}
