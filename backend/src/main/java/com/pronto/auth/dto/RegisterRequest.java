package com.pronto.auth.dto;

import com.pronto.users.entity.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The {@code data} part of the {@code multipart/form-data} body for
 * {@code POST /api/auth/register}. Customer and Professional registration are separated
 * at the DTO level: {@code customer}/{@code professional} are mutually exclusive
 * role-specific payloads (see {@link CustomerRegistrationData}/
 * {@link ProfessionalRegistrationData}) rather than one flat object of nullable fields.
 * Exactly one of them is populated, matching {@link #role} — enforced in
 * {@code AuthService}, not by Bean Validation annotations here, since "required *iff*
 * role == X" is a cross-field rule.
 *
 * <p>{@code confirmPassword} is deliberately NOT a field here — it's frontend-only
 * validation (backend registration flow separation task §3), never sent to/persisted by
 * the backend.
 *
 * <p>A Professional registration's required verification document (and optional profile
 * photo) travel as separate {@code multipart/form-data} file parts on the same request,
 * not as fields on {@link ProfessionalRegistrationData} — see that class's Javadoc.
 * See {@code docs/architecture/api-contract.md} §2.1.
 */
public record RegisterRequest(
        @NotNull UserRole role,
        @NotBlank @Size(min = 2, max = 150) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        /**
         * <b>Production MS1.</b> Moved here from {@code customer.phone} and now required for
         * <em>both</em> roles. Every real Pronto account has an email and a phone, and both belong
         * to the same {@code users} row — a professional whose phone number lived nowhere could
         * neither be reached nor use it to sign in. Accepted in ordinary Israeli spelling
         * ({@code 050-123-4567}, {@code +972501234567}, {@code 00972501234567}); canonicalized to
         * E.164 by {@code auth.service.PhoneNumberNormalizer} before it is stored, so all three
         * become one identity.
         *
         * <p>Validated for shape and reachability by that normalizer rather than by an annotation
         * here — {@code @Pattern} would be a second, staler copy of the Israeli numbering plan.
         */
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(min = 8) String password,
        @Valid CustomerRegistrationData customer,
        @Valid ProfessionalRegistrationData professional
) {
}
