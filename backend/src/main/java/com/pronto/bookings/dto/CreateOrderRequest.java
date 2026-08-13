package com.pronto.bookings.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Wire shape for {@code POST /api/bookings/orders}. See
 * {@code docs/architecture/api-contract-bookings.md} §2.4. Existence/ownership/state
 * validation for each field happens in {@code BookingsService} (see that section's Behavior
 * steps and field-validation table) — Bean Validation here only covers presence/shape
 * (non-null, positive).
 */
public record CreateOrderRequest(
        @NotNull @Positive Long issueId,
        @NotNull @Positive Long professionalId,
        @NotNull @Positive Long slotId
) {
}
