package com.pronto.professionals.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/admin/professionals/{professionalId}} — everything an operator needs to decide,
 * on one screen. MS1 (D-F).
 *
 * <p>The onboarding breakdown is the point of this DTO. {@code onboardingComplete} alone tells an
 * operator that something is missing but not what, which turns "approve or reject" into guesswork
 * and makes a rejection reason impossible to write honestly. So the raw material is reported
 * alongside it: whether a verification document exists, and which sub-services were actually
 * selected. The remaining component — an enabled working-hours day — is deliberately not queried
 * separately: {@code professionals} must not take a Java-level dependency on the
 * {@code availability} package (which already depends on it), and with the other two visible the
 * operator can already tell it apart. {@code onboardingComplete} itself comes from
 * {@link com.pronto.professionals.ProfessionalEligibility#ONBOARDING_COMPLETE_JPQL}, not from
 * re-deriving the rule over these fields.
 *
 * <p><b>The verification document is not here.</b> No key, no URL — see
 * {@code GET /api/admin/professionals/{professionalId}/verification-document}, which mints a
 * short-lived URL on demand. Embedding one in this response would mean every list-then-open
 * traversal minted a bearer capability for a private compliance document whether or not anyone
 * looked at it, and would put that URL in every intermediate cache and browser history along the
 * way. {@link #hasVerificationDocument} is the only thing this response says about it.
 *
 * @param approvalRejectionReason the reason currently in force, i.e. non-{@code null} only while
 *                                {@code approvalStatus = REJECTED} — MS1 records the decision in
 *                                force, not a history of superseded ones
 */
public record ProfessionalReviewDetailResponse(
        Long professionalId,
        Long userId,
        String fullName,
        String email,
        Long categoryId,
        String serviceArea,
        String city,
        String bio,
        BigDecimal basePrice,
        String approvalStatus,
        boolean bookable,
        boolean hasVerificationDocument,
        List<Long> subServiceIds,
        boolean onboardingComplete,
        Instant registeredAt,
        Instant approvalReviewedAt,
        Long approvalReviewedBy,
        String approvalRejectionReason
) {
}
