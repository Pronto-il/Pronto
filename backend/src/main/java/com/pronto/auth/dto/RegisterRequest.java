package com.pronto.auth.dto;

import com.pronto.users.entity.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Wire shape for {@code POST /api/auth/register}. One flat JSON object for both roles —
 * {@code categoryId}/{@code serviceArea}/{@code basePrice} carry no Bean Validation
 * annotations deliberately: they're required *iff* {@code role == PROFESSIONAL}, validated
 * conditionally in {@code AuthService} rather than unconditionally here, since they don't
 * exist at all for a customer registration. See
 * {@code docs/architecture/api-contract.md} §2.1.
 */
public record RegisterRequest(
        @NotNull UserRole role,
        @NotBlank @Size(min = 2, max = 150) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8) String password,
        Long categoryId,
        String serviceArea,
        BigDecimal basePrice
) {
}
