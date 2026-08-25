package com.pronto.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * {@code customer}-role-specific registration payload, nested under
 * {@link RegisterRequest} iff {@code role == CUSTOMER}. Bean Validation only cascades
 * into this record (and thus into {@link #defaultAddress}'s own field rules) when the
 * enclosing {@code customer} field is
 * non-null — a {@code CUSTOMER} registration with no {@code customer} object at all is
 * caught separately, in {@code AuthService}, since that's a cross-field ("required *iff*
 * this role") rule Bean Validation annotations can't express on their own. See backend
 * registration flow separation task §4/§18-19.
 *
 * <p><b>Production MS1 removed {@code phone} from this record.</b> It was here because phone was
 * once a customer-only contact detail; it is now an identity that every account of every role must
 * have, so it lives on {@link RegisterRequest} alongside email and password. Nothing about this
 * record's remaining field changed — {@code defaultAddress} is still customer-only and still
 * required.
 */
public record CustomerRegistrationData(
        @NotNull @Valid DefaultAddressRequest defaultAddress
) {
}
