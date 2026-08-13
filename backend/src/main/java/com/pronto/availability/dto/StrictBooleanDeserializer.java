package com.pronto.availability.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Deserializes a JSON value strictly as a boolean literal ({@code true}/{@code false}),
 * rejecting Jackson's default lenient scalar coercion — by default, Jackson silently
 * coerces JSON numbers to {@link Boolean} (nonzero → {@code true}, {@code 0} → {@code false}),
 * which is wrong for {@link SosAvailabilityRequest#isAvailable()}: per
 * {@code docs/architecture/api-contract-bookings.md} §2.14, a non-boolean JSON value for
 * that field must be rejected as {@code 400 VALIDATION_ERROR}, not silently accepted.
 *
 * <p>Deliberately scoped to this single field via {@code @JsonDeserialize} on
 * {@link SosAvailabilityRequest#isAvailable()}, rather than a global {@code ObjectMapper}
 * coercion-config change — this cannot affect any other {@code Boolean}-typed field
 * elsewhere in the app, so it carries no risk to Milestone 1-3's already-shipped endpoints.
 *
 * <p>Any non-boolean-literal token (number, string, array, object, ...) delegates to
 * Jackson's standard "cannot coerce" failure path via {@link
 * DeserializationContext#handleUnexpectedToken}, the same path an array/object already hits
 * today — surfaced as {@code 400 VALIDATION_ERROR} by
 * {@code common.exception.GlobalExceptionHandler}'s {@code HttpMessageNotReadableException}
 * handler.
 */
final class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_TRUE) {
            return Boolean.TRUE;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return Boolean.FALSE;
        }
        return (Boolean) ctxt.handleUnexpectedToken(Boolean.class, p);
    }
}
