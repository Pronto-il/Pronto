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
 * <p>{@code phoneVerified} is what the client uses, together with {@code phoneVerificationRequired},
 * to decide whether to show the phone-capture prompt — so a user is asked before they hit
 * {@code PHONE_VERIFICATION_REQUIRED} rather than after. The prompt is UX; the rule itself is
 * {@code users.service.ContactVerificationGuard}. See {@code docs/architecture/api-contract.md}
 * §2.4.
 *
 * <p><b>{@code emailVerified} and {@code phoneVerified} report the columns, always, and are never
 * adjusted by policy.</b> They answer "was this channel proved", which stays {@code false} for an
 * account created while OTP verification was switched off. Making them report {@code true} in that
 * state would be the easy way to stop the client nagging, and it would put a lie in the one record
 * that says who still owes what — the record that decides who gets asked to verify when
 * verification is turned back on.
 *
 * @param phoneVerificationRequired whether this deployment still asks accounts to prove their phone
 *                                  number at all ({@code auth.config.VerificationPolicy}, which is
 *                                  itself gated by {@code OTP_VERIFICATION_ENABLED}). Added so the
 *                                  client can tell "unproved and being asked" from "unproved and
 *                                  nobody is asking" without either guessing or being lied to by
 *                                  {@code phoneVerified}. When {@code false} the capture screen
 *                                  redirects away instead of offering to send a code nothing will
 *                                  redeem.
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
        boolean phoneVerified,
        boolean phoneVerificationRequired
) {
}
