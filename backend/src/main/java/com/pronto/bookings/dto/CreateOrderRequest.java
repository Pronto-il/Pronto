package com.pronto.bookings.dto;

import com.pronto.maps.AddressAccessFields;
import com.pronto.maps.HouseNumbers;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
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
 * <p>Optional still means shape-checked: {@code serviceApartment}/{@code serviceFloor} are digits
 * only and {@code serviceEntrance} is at most two letters-or-digits, per
 * {@code maps.AddressAccessFields}. Each pattern admits the empty string, so omitting them is
 * unaffected — this only stops the order's address snapshot carrying a value the address form
 * would never have produced.
 *
 * <p><b>{@code slotId} is dropped entirely (design §9.2.2)</b> — not kept, even as an
 * optional/ignored field, for backward compatibility (there is no production data and no
 * external API consumer to preserve compatibility for). {@code bookedStart} replaces it:
 * required, and validated strictly in the future in {@code BookingsService} (same "strictly
 * future" convention the retired slot-claim path already used). {@code bookedEnd} is
 * deliberately **not** a field here at all — it is always computed server-side as
 * {@code bookedStart + BookingsService.DEFAULT_JOB_DURATION_MINUTES}, never accepted from the
 * client, so a malicious/buggy client can never request an arbitrary-length booking.
 *
 * <p><b>The {@code servicePlaceId}/{@code serviceFormattedAddress}/{@code serviceLatitude}/
 * {@code serviceLongitude} group identifies the place the customer SELECTED</b> ({@code V55}),
 * for the "another address for this booking" case. Validated together by
 * {@code maps.service.SelectedPlaceValidator} — a partial claim is refused.
 *
 * <p><b>They are conditionally required, and the condition is deliberate.</b> A customer booking
 * to their own saved default address may omit them, because that address is already on their
 * {@code users} row and may legitimately predate address validation; requiring a re-selection
 * would stop existing customers mid-booking to re-enter an address that has been working. Any
 * <em>other</em> address is new text nobody has confirmed, and must carry a selected place.
 * {@code BookingsService} decides which case applies by comparing the submitted address against
 * the caller's own stored default with the {@code V50} address digest — a comparison a caller
 * cannot exploit, since it only ever admits an address already saved on their own account.
 */
public record CreateOrderRequest(
        @NotNull @Positive Long issueId,
        @NotNull @Positive Long professionalId,
        @NotNull Instant bookedStart,
        @NotBlank String serviceCity,
        @NotBlank String serviceStreet,
        @NotBlank @Pattern(regexp = HouseNumbers.PATTERN, message = HouseNumbers.MESSAGE)
        String serviceHouseNumber,
        @Pattern(regexp = AddressAccessFields.APARTMENT_PATTERN,
                message = AddressAccessFields.APARTMENT_MESSAGE) String serviceApartment,
        @Pattern(regexp = AddressAccessFields.FLOOR_PATTERN,
                message = AddressAccessFields.FLOOR_MESSAGE) String serviceFloor,
        @Pattern(regexp = AddressAccessFields.ENTRANCE_PATTERN,
                message = AddressAccessFields.ENTRANCE_MESSAGE) String serviceEntrance,
        String serviceAddressNotes,
        @Size(max = 255) String servicePlaceId,
        @Size(max = 500) String serviceFormattedAddress,
        BigDecimal serviceLatitude,
        BigDecimal serviceLongitude
) {
}
