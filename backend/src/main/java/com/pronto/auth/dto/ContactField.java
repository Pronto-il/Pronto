package com.pronto.auth.dto;

/**
 * Which of the two contact identities an {@link AvailabilityRequest} is asking about.
 *
 * <p>These are exactly the two columns registration holds unique — {@code ux_users_email} and
 * {@code ux_users_phone} — and exactly the two field errors a registration form has to be able to
 * put under the right input. It is an enum rather than a free string so the endpoint cannot be
 * pointed at some other column by a caller who guesses a name.
 */
public enum ContactField {
    EMAIL,
    PHONE
}
