package com.pronto.professionals.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wire shape for {@code PUT /api/professionals/me} — an allowlist DTO. Deliberately excludes
 * (by omission — no field exists for the client to set them through): {@code id},
 * {@code userId}, {@code approvalStatus}, {@code reliabilityScore}, any rating/review-count
 * field (both derived, never client-writable), {@code profileImageKey} (its own endpoint,
 * {@code POST /api/professionals/me/profile-image}), and {@code createdAt}/{@code updatedAt}.
 *
 * <p><b>MS4 (§18).</b> Everything registration collects about coverage and trades is editable
 * here, because a professional who moved, expanded or picked the wrong option at signup must be
 * able to fix it without support: free-text {@code serviceArea}/{@code city} are replaced by
 * {@code serviceRegionId} + {@code serviceCityIds} + {@code baseCityId}, and {@code categoryIds}
 * is new. {@code categoryId} used to be excluded from this DTO precisely because a professional
 * could not change their single trade at all; that restriction is what MS4 lifts.
 *
 * <p>Authorization is unchanged and unchanging: the route is {@code /me}, so the professional
 * being edited is always the caller's own — see {@code ProfessionalsService#resolveOwnProfessional}.
 * No field here names a professional, so widening the DTO cannot widen who it can be applied to.
 *
 * @param serviceCityIds at least one; every one inside {@code serviceRegionId}
 * @param baseCityId     must be one of {@code serviceCityIds} — the city ETA is measured from
 *                       has to be a city they actually serve
 * @param categoryIds    at least one; every one an existing {@code categories} row
 */
public record UpdateProfessionalProfileRequest(
        @NotBlank @Size(max = 150) String fullName,
        @NotNull Long serviceRegionId,
        @NotEmpty List<Long> serviceCityIds,
        @NotNull Long baseCityId,
        @NotEmpty List<Long> categoryIds,
        @Size(max = 2000) String bio,
        @NotNull @PositiveOrZero BigDecimal basePrice
) {
}
