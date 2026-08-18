package com.pronto.users.dto;

import com.pronto.users.entity.UserRole;

/**
 * Response body for {@code GET /api/users/me}. {@code professional} is {@code null} for a
 * {@code CUSTOMER} caller. See {@code docs/architecture/api-contract.md} §2.4.
 */
public record UserMeResponse(
        Long id,
        String fullName,
        String email,
        UserRole role,
        boolean emailVerified,
        ProfessionalInfo professional,
        DefaultAddressInfo defaultAddress
) {
}
