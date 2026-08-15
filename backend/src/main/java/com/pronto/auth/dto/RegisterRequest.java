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
        @NotBlank @Size(min = 8) String password,
        @Valid CustomerRegistrationData customer,
        @Valid ProfessionalRegistrationData professional
) {
}
