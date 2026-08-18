package com.pronto.users.dto;

import com.pronto.users.entity.UserRole;

/**
 * Response body for {@code GET /api/users/me}. {@code professional} is {@code null} for a
 * {@code CUSTOMER} caller. {@code phone} — new, professional weekly availability calendar
 * design §9.1 — is {@code null} for a {@code PROFESSIONAL} caller and for a {@code CUSTOMER}
 * with no recorded phone (pre-V28 accounts), same nullability/placement convention as
 * {@code defaultAddress}. See {@code docs/architecture/api-contract.md} §2.4.
 */
public record UserMeResponse(
        Long id,
        String fullName,
        String email,
        UserRole role,
        boolean emailVerified,
        ProfessionalInfo professional,
        DefaultAddressInfo defaultAddress,
        String phone
) {
}
