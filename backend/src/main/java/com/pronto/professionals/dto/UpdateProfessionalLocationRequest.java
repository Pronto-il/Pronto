package com.pronto.professionals.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Body of {@code PUT /api/professionals/me/location} — one device position reading.
 *
 * <p>Field names and ranges mirror {@code sos.dto.CreateSosRequestRequest}'s existing
 * latitude/longitude pair exactly, including the same {@code @DecimalMin}/{@code @DecimalMax}
 * bounds, so the two coordinate-carrying request bodies in this API cannot disagree about what a
 * legal coordinate is. The Bean Validation annotations here are the cheap first pass; the
 * authoritative check (and the clock-skew rule) lives in
 * {@code ProfessionalLocationService#record}, which is also reachable from the arrival flow.
 *
 * <p>There is deliberately no {@code professionalId} field. The subject is always the caller —
 * taking it from the body would create an endpoint where one professional can write another's
 * position, which is both a privacy breach and a way to poison somebody else's ETA.
 *
 * @param accuracyMeters the device's own reported horizontal accuracy. <b>Required</b>: a fix
 *                       with no accuracy figure cannot be quality-checked, and MS2's whole
 *                       position is that an unqualified fix must not be treated as a precise one.
 *                       This is {@code coords.accuracy} from the W3C Geolocation API, which every
 *                       browser that supports geolocation at all populates.
 * @param capturedAt     when the <em>device</em> took the reading. Client-supplied and never
 *                       trusted alone — the server stamps its own receive time, and freshness is
 *                       the stricter of the two.
 */
public record UpdateProfessionalLocationRequest(

        @NotNull
        @DecimalMin("-90.0") @DecimalMax("90.0")
        BigDecimal latitude,

        @NotNull
        @DecimalMin("-180.0") @DecimalMax("180.0")
        BigDecimal longitude,

        @NotNull
        @Positive
        BigDecimal accuracyMeters,

        @NotNull
        Instant capturedAt
) {
}
