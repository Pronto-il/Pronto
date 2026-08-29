package com.pronto.users.dto;

import com.pronto.maps.AddressAccessFields;
import com.pronto.maps.HouseNumbers;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A customer's home address, as both {@code PUT /api/users/me} (nested under
 * {@link UpdateUserMeRequest#defaultAddress()}) and {@code PUT /api/users/me/default-address}
 * (the whole body) take it.
 *
 * <p>Same shape and validation as {@code auth.dto.DefaultAddressRequest}, <b>defined
 * independently</b>: reusing that record would add a {@code users -> auth} package dependency
 * edge for the sake of eleven field declarations. Mirrors {@link DefaultAddressInfo}'s existing
 * independent-shape convention. {@code @Size} caps mirror the {@code users.default_*} column
 * lengths (V20); {@code houseNumber} is digits only (see {@code maps.HouseNumbers}), and the
 * optional {@code apartment}/{@code floor}/{@code entrance} carry their own shape rules (see
 * {@code maps.AddressAccessFields}) while remaining optional.
 *
 * <p>Extracted from {@code UpdateUserMeRequest.Address} when the home address gained a second
 * endpoint of its own — the booking flow's "הפוך את זה לכתובת הבית", which has an address and
 * nothing else and must not be made to resend the customer's name and phone number to save it.
 *
 * <p><b>The last four fields identify the place the customer actually SELECTED</b> ({@code V55}),
 * and are <b>required on every endpoint that takes this record</b>: saving a home address is
 * exactly the moment a legacy free-text one is expected to become a validated one. Bean Validation
 * cannot express "all four or none", so they are individually optional here and enforced together
 * by {@code maps.service.SelectedPlaceValidator} — the same presence-and-shape-here /
 * rules-in-the-service-layer split the rest of this codebase uses.
 *
 * <p>A pre-{@code V55} address that nobody edits keeps working untouched. Nothing is backfilled.
 */
public record CustomerAddressRequest(
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 150) String street,
        @NotBlank @Size(max = 20)
        @Pattern(regexp = HouseNumbers.PATTERN, message = HouseNumbers.MESSAGE) String houseNumber,
        @Size(max = 20)
        @Pattern(regexp = AddressAccessFields.APARTMENT_PATTERN,
                message = AddressAccessFields.APARTMENT_MESSAGE) String apartment,
        @Size(max = 20)
        @Pattern(regexp = AddressAccessFields.FLOOR_PATTERN,
                message = AddressAccessFields.FLOOR_MESSAGE) String floor,
        @Size(max = 20)
        @Pattern(regexp = AddressAccessFields.ENTRANCE_PATTERN,
                message = AddressAccessFields.ENTRANCE_MESSAGE) String entrance,
        @Size(max = 500) String addressNotes,
        @Size(max = 255) String placeId,
        @Size(max = 500) String formattedAddress,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
