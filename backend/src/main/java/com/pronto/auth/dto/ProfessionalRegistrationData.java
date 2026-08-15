package com.pronto.auth.dto;

import java.math.BigDecimal;

/**
 * {@code professional}-role-specific registration payload, nested under
 * {@link RegisterRequest} iff {@code role == PROFESSIONAL}. Deliberately carries no Bean
 * Validation annotations, same reasoning as the flat DTO this replaced: these fields are
 * required *iff* {@code role == PROFESSIONAL}, which Bean Validation can't express
 * conditionally on a sibling field — validated in {@code AuthService} instead. See
 * backend registration flow separation task §7-10/§18-19.
 *
 * <p>The verification document and optional profile photo are NOT fields here — they're
 * uploaded as separate {@code multipart/form-data} parts on the same
 * {@code POST /api/auth/register} request (see {@code auth.controller.AuthController}),
 * since a canonical object-storage key can't exist yet for a Professional account that
 * hasn't been created — unlike {@code professionals.dto.UpdateProfessionalProfileRequest}'s
 * later self-service profile-image flow, which already has an authenticated caller.
 */
public record ProfessionalRegistrationData(
        Long categoryId,
        String serviceArea,
        BigDecimal basePrice
) {
}
