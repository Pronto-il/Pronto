package com.pronto.bookings.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Wire shape for {@code POST /api/bookings/sos-orders}. See
 * {@code docs/architecture/api-contract-bookings.md} §2.13. One fewer field than
 * {@link CreateOrderRequest} — SOS has no slot selection at all. Existence/ownership/state
 * validation for each field happens in {@code BookingsService} (see that section's Behavior
 * steps and field-validation table) — Bean Validation here only covers presence/shape
 * (non-null, positive).
 */
public record CreateSosOrderRequest(
        @NotNull @Positive Long issueId,
        @NotNull @Positive Long professionalId
) {
}
