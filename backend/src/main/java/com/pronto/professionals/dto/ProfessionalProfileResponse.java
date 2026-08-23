package com.pronto.professionals.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response body for {@code GET /api/professionals/me} (self view) and
 * {@code GET /api/professionals/{professionalId}} (detail view, either role).
 * {@code favorited} is only ever populated on the {@code {professionalId}} detail endpoint
 * for a {@code CUSTOMER} caller — always {@code null} on {@code /me} (a professional can't
 * favorite themself, and this field is meaningless for a self-view).
 *
 * @param approvalStatus <b>MS1 (D-G): self-view only</b>, {@code null} for every other caller.
 *                       This field used to be returned to any authenticated caller, which was
 *                       harmless only for as long as the column was permanently {@code APPROVED}.
 *                       Now that it carries a real decision, returning it to a browsing customer
 *                       would tell them "this professional was rejected" — a judgment about a
 *                       named person, disclosed to someone with no business knowing it. The
 *                       professional themselves must see it; nobody else needs to.
 * @param bookable       The neutral replacement everyone gets: is this professional
 *                       marketplace-eligible ({@code approvalStatus = APPROVED} <em>and</em>
 *                       onboarding complete — see
 *                       {@link com.pronto.professionals.ProfessionalEligibility}). Enough for the
 *                       UI to withhold a booking affordance it would otherwise offer into a dead
 *                       end, and it reveals nothing about which of the several possible reasons
 *                       applies.
 */
public record ProfessionalProfileResponse(
        Long id,
        Long categoryId,
        String fullName,
        String serviceArea,
        String city,
        String bio,
        BigDecimal basePrice,
        String profileImageUrl,
        BigDecimal averageRating,
        long reviewCount,
        String approvalStatus,
        boolean bookable,
        Boolean favorited,
        Instant createdAt,
        Instant updatedAt
) {
}
