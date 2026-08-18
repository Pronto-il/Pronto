package com.pronto.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code customer}-role-specific registration payload, nested under
 * {@link RegisterRequest} iff {@code role == CUSTOMER}. Bean Validation only cascades
 * into this record (and thus into {@link #defaultAddress}'s own field rules, and into
 * {@link #phone}'s own {@code @NotBlank} rule) when the enclosing {@code customer} field is
 * non-null — a {@code CUSTOMER} registration with no {@code customer} object at all is
 * caught separately, in {@code AuthService}, since that's a cross-field ("required *iff*
 * this role") rule Bean Validation annotations can't express on their own. See backend
 * registration flow separation task §4/§18-19.
 *
 * <p>{@code phone} — new field, professional weekly availability calendar design §9.1: a
 * {@code CUSTOMER}'s phone number, required at registration, mirroring
 * {@code defaultAddress}'s own required-field treatment at the same validation tier (not
 * collected for {@code PROFESSIONAL} registration — see {@code ProfessionalRegistrationData},
 * unchanged). {@code @Size(max = 20)} mirrors the {@code users.phone} column length (V28).
 */
public record CustomerRegistrationData(
        @NotNull @Valid DefaultAddressRequest defaultAddress,
        @NotBlank @Size(max = 20) String phone
) {
}
