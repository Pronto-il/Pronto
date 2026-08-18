package com.pronto.bookings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Wire shape for {@code POST /api/bookings/orders}. See
 * {@code docs/architecture/api-contract-bookings.md} §2.4. Existence/ownership/state
 * validation for each field happens in {@code BookingsService} (see that section's Behavior
 * steps and field-validation table) — Bean Validation here only covers presence/shape
 * (non-null, positive).
 *
 * <p>{@code serviceCity}/{@code serviceStreet}/{@code serviceHouseNumber} are required
 * (service-address snapshot, §1 classification item 5); {@code serviceApartment}/
 * {@code serviceFloor}/{@code serviceEntrance}/{@code serviceAddressNotes} are optional.
 */
public record CreateOrderRequest(
        @NotNull @Positive Long issueId,
        @NotNull @Positive Long professionalId,
        @NotNull @Positive Long slotId,
        @NotBlank String serviceCity,
        @NotBlank String serviceStreet,
        @NotBlank String serviceHouseNumber,
        String serviceApartment,
        String serviceFloor,
        String serviceEntrance,
        String serviceAddressNotes
) {
}
