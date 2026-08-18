package com.pronto.bookings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * Wire shape for {@code POST /api/bookings/orders}. See
 * {@code docs/architecture/api-contract-bookings.md} §2.4 and the professional weekly
 * availability calendar design §9.2.2. Existence/ownership/state validation for each field
 * happens in {@code BookingsService} (see that section's Behavior steps and field-validation
 * table) — Bean Validation here only covers presence/shape (non-null, positive).
 *
 * <p>{@code serviceCity}/{@code serviceStreet}/{@code serviceHouseNumber} are required
 * (service-address snapshot, §1 classification item 5); {@code serviceApartment}/
 * {@code serviceFloor}/{@code serviceEntrance}/{@code serviceAddressNotes} are optional.
 *
 * <p><b>{@code slotId} is dropped entirely (design §9.2.2)</b> — not kept, even as an
 * optional/ignored field, for backward compatibility (there is no production data and no
 * external API consumer to preserve compatibility for). {@code bookedStart} replaces it:
 * required, and validated strictly in the future in {@code BookingsService} (same "strictly
 * future" convention the retired slot-claim path already used). {@code bookedEnd} is
 * deliberately **not** a field here at all — it is always computed server-side as
 * {@code bookedStart + BookingsService.DEFAULT_JOB_DURATION_MINUTES}, never accepted from the
 * client, so a malicious/buggy client can never request an arbitrary-length booking.
 */
public record CreateOrderRequest(
        @NotNull @Positive Long issueId,
        @NotNull @Positive Long professionalId,
        @NotNull Instant bookedStart,
        @NotBlank String serviceCity,
        @NotBlank String serviceStreet,
        @NotBlank String serviceHouseNumber,
        String serviceApartment,
        String serviceFloor,
        String serviceEntrance,
        String serviceAddressNotes
) {
}
