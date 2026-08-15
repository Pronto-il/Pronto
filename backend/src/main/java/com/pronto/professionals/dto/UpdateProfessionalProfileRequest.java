package com.pronto.professionals.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Wire shape for {@code PUT /api/professionals/me} — an allowlist DTO. Deliberately excludes
 * (by omission — no field exists for the client to set them through): {@code id},
 * {@code userId}, {@code categoryId}, {@code approvalStatus}, {@code reliabilityScore}, any
 * rating/review-count field (both derived, never client-writable), {@code profileImageKey}
 * (its own endpoint, {@code POST /api/professionals/me/profile-image}), and
 * {@code createdAt}/{@code updatedAt}.
 */
public record UpdateProfessionalProfileRequest(
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Size(max = 150) String serviceArea,
        @NotBlank @Size(max = 100) String city,
        @Size(max = 2000) String bio,
        @NotNull @PositiveOrZero BigDecimal basePrice
) {
}
