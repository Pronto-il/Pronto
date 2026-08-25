package com.pronto.users.dto;

import com.pronto.users.entity.UserRole;

/**
 * Response body for {@code GET /api/users/me}. {@code professional} is {@code null} for a
 * {@code CUSTOMER} caller.
 *
 * <p><b>Production MS1 changed {@code phone} and added {@code phoneVerified}.</b> {@code phone} is
 * now returned for every role in canonical E.164 — it used to be blanked for a
 * {@code PROFESSIONAL}, which was correct while the column was customer-only contact detail and is
 * wrong now that it is the account's second identity. It is {@code null} only on a legacy row that
 * has never supplied one.
 *
 * <p>{@code phoneVerified} is what the client uses to decide whether to show the phone-capture
 * prompt, so a user is asked before they hit {@code PHONE_VERIFICATION_REQUIRED} rather than after.
 * The prompt is UX; the rule itself is {@code users.service.ContactVerificationGuard}.
 * See {@code docs/architecture/api-contract.md} §2.4.
 */
public record UserMeResponse(
        Long id,
        String fullName,
        String email,
        UserRole role,
        boolean emailVerified,
        ProfessionalInfo professional,
        DefaultAddressInfo defaultAddress,
        String phone,
        boolean phoneVerified
) {
}
