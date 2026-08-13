package com.pronto.availability.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;

/**
 * Wire shape for {@code PUT /api/availability/sos-availability}. See
 * {@code docs/architecture/api-contract-bookings.md} §2.14. {@code Boolean} (not primitive
 * {@code boolean}) so a missing field is distinguishable from {@code false} at bean-validation
 * time — {@code @NotNull} rejects a missing/{@code null} value as {@code 400
 * VALIDATION_ERROR}; a non-boolean JSON value fails at Jackson deserialization, also surfaced
 * as {@code 400 VALIDATION_ERROR} by {@code common.exception.GlobalExceptionHandler}'s
 * {@code HttpMessageNotReadableException} handler. {@code @JsonDeserialize} pins this to
 * {@link StrictBooleanDeserializer} so a JSON number (e.g. {@code 1}/{@code 0}) is rejected
 * too, instead of Jackson's default lenient numeric-to-boolean coercion silently accepting
 * it — see that class's javadoc.
 */
public record SosAvailabilityRequest(
        @NotNull @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean isAvailable
) {
}
