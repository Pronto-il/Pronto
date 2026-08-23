package com.pronto.professionals.dto;

import java.time.Instant;

/**
 * One row of the operator queue, {@code GET /api/admin/professionals}. MS1 (D-F).
 *
 * <p>Deliberately lean: enough to decide which professional to open next (who, which category,
 * how long they have been waiting) and nothing that would make the list itself a bulk export of
 * professional data. Everything a review actually needs is on
 * {@link ProfessionalReviewDetailResponse}, one professional at a time.
 *
 * @param onboardingComplete whether this professional has the sub-services, working hours and
 *                           verification document that make approval meaningful. Surfaced here
 *                           because approving someone whose onboarding is incomplete leaves them
 *                           non-bookable, and the operator should be able to see that before
 *                           spending a decision on it rather than after
 */
public record ProfessionalApprovalSummary(
        Long professionalId,
        Long userId,
        String fullName,
        String email,
        Long categoryId,
        String serviceArea,
        String city,
        String approvalStatus,
        boolean onboardingComplete,
        Instant registeredAt,
        Instant approvalReviewedAt
) {
}
