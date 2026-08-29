package com.pronto.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/auth/availability}.
 *
 * <p><b>A body rather than query parameters, and {@code POST} rather than {@code GET}</b>, even
 * though the operation is a read and would otherwise be a natural {@code GET}. An email address
 * and a phone number are the two most identifying values this API handles, and a query string is
 * the one part of a request that is copied everywhere by default: the ALB access log, the browser's
 * history and address bar, the {@code Referer} header sent to any third party the page then loads,
 * and any intermediary cache. Putting the value in a body keeps it out of all of them. Every other
 * route in {@code /api/auth/*} that names a person is a {@code POST} for the same reason.
 *
 * @param field which contact detail {@link #value} is
 * @param value the address or number as the customer typed it — not normalized by the caller;
 *              {@code EmailNormalizer}/{@code PhoneNumberNormalizer} canonicalize it server-side,
 *              exactly as {@code AuthAccountWriter#createAccount} does, so that this endpoint and
 *              registration cannot disagree about what "the same value" means
 */
public record AvailabilityRequest(
        @NotNull ContactField field,
        @NotBlank @Size(max = 255) String value
) {
}
