package com.pronto.auth.dto;

import jakarta.validation.Valid;

/**
 * {@code customer}-role-specific registration payload, nested under
 * {@link RegisterRequest} iff {@code role == CUSTOMER}. Bean Validation only cascades
 * into this record (and thus into {@link #defaultAddress}'s own field rules) when the
 * enclosing {@code customer} field is non-null. See backend registration flow separation
 * task §4/§18-19.
 *
 * <p><b>Production MS1 removed {@code phone} from this record.</b> It was here because phone was
 * once a customer-only contact detail; it is now an identity that every account of every role must
 * have, so it lives on {@link RegisterRequest} alongside email and password.
 *
 * <p><b>The address-flow redesign made {@link #defaultAddress} optional</b>, and with it this
 * whole object: a {@code CUSTOMER} registration may now legitimately carry
 * {@code "customer": null}. An address is a property of a <em>job</em>, not of an account — the
 * booking flow collects it after AI classification, immediately before anything needs it, and
 * saves it to the profile only when the customer asks ("הפוך את זה לכתובת הבית") or edits it on
 * the profile screen. Requiring one to open an account bought a mandatory extra registration
 * screen and a default that was wrong for anyone booking on somebody else's behalf.
 *
 * <p>Nothing about a <em>supplied</em> address changed: it is still cascaded into by
 * {@code @Valid}, and {@code AuthService} still requires it to carry a selected place, so the
 * seed/demo paths and any client that does send one are unaffected. What was removed is the
 * requirement, not the capability.
 */
public record CustomerRegistrationData(
        @Valid DefaultAddressRequest defaultAddress
) {
}
