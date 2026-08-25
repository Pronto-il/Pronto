package com.pronto.bookings.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Body of {@code POST /api/bookings/orders/{orderId}/arrived} — the professional's device
 * position at the moment they claim to have arrived.
 *
 * <p><b>Why this endpoint takes a body at all, when the platform already stores the
 * professional's current location.</b> It could read the stored position instead, and that would
 * be one fewer field to validate — but the stored position is up to
 * {@code pronto.location.professional-freshness} (ten minutes) old by design, which is fine for
 * estimating a journey and nowhere near good enough to be the sole evidence for "I am at this
 * door right now". Arrival requires a fix taken at the moment of the claim, held to a much
 * tighter age and accuracy bar ({@code pronto.location.arrival-max-age},
 * {@code pronto.location.arrival-max-accuracy-meters}), so the client takes a fresh reading and
 * sends it here.
 *
 * <p>The same reading is also written through to {@code professional_locations} as a side effect,
 * since a fresh fix is a fresh fix and discarding it would be wasteful.
 *
 * <p><b>The customer's coordinates are never sent to the client.</b> The comparison happens
 * entirely on the server — see {@code BookingsService#arrived}. An endpoint that returned the
 * destination for the client to check would both leak the address and let any client claim to be
 * anywhere.
 */
public record ArrivalRequest(

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
