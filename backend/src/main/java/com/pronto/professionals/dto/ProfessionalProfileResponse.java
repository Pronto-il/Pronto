package com.pronto.professionals.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
 * @param categoryIds    <b>MS4</b>, replacing the single {@code categoryId}: every category this
 *                       professional serves, in {@code categories.display_order}. The first entry
 *                       is what compact surfaces show as the primary trade — an ordering
 *                       convention, not a stored flag (see
 *                       {@link com.pronto.professionals.entity.ProfessionalCategory}).
 * @param serviceRegionId <b>MS4</b>, replacing free-text {@code serviceArea}: a canonical
 *                       {@code service_regions} id, with {@code serviceRegionNameHe} alongside so
 *                       a client can render it without a second lookup. Both {@code null} for a
 *                       pre-MS4 professional {@code V44} could not place — the profile editor
 *                       then asks them to choose, which is the honest outcome.
 * @param serviceCityIds <b>MS4</b>: the canonical cities they serve, in catalogue order, with
 *                       {@code serviceCityNamesHe} alongside. {@code baseCityId} is always one of
 *                       them, and is the city ETA is measured from.
 */
public record ProfessionalProfileResponse(
        Long id,
        List<Long> categoryIds,
        String fullName,
        Long serviceRegionId,
        String serviceRegionNameHe,
        Long baseCityId,
        String city,
        List<Long> serviceCityIds,
        List<String> serviceCityNamesHe,
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
