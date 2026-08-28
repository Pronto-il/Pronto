package com.pronto.users.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for {@code PUT /api/users/me} — {@code CUSTOMER}-only. See
 * {@code docs/architecture/api-contract.md} §2.6 and
 * {@code docs/architecture/product-ms10-profile-redesign-design.md} §4.2.
 *
 * <p>{@code fullName}/{@code phone} mirror the same {@code @NotBlank}/{@code @Size}
 * constraints as {@code auth.dto.CustomerRegistrationData} (there is no "leave phone unset"
 * state for a {@code CUSTOMER} today, so this update endpoint doesn't introduce one).
 * {@code defaultAddress} is always required in full — no partial-address update — and is a
 * locally-defined nested record ({@link Address}) rather than reusing
 * {@code auth.dto.DefaultAddressRequest} directly: reusing it would add a new
 * {@code users -> auth} package dependency edge. Mirrors {@link DefaultAddressInfo}'s
 * existing independent-shape convention instead (identical field set/validation, no
 * cross-package edge).
 */
public record UpdateUserMeRequest(
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Size(max = 20) String phone,
        @NotNull @Valid Address defaultAddress
) {

    /**
     * Same shape/validation as {@code auth.dto.DefaultAddressRequest}, defined independently.
     *
     * <p>Includes the selected-place fields ({@code V55}), and <b>requires them</b>: this endpoint
     * is exactly "the customer is editing their saved address", which is the moment a legacy
     * free-text row is expected to become a validated one. A pre-{@code V55} address keeps working
     * untouched for as long as nobody edits it — see {@code UsersService#updateMe} — but an edit
     * that submitted new free text and kept the old, now-wrong coordinates is the one outcome that
     * must not be possible.
     */
    public record Address(
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 150) String street,
            @NotBlank @Size(max = 20) String houseNumber,
            @Size(max = 20) String apartment,
            @Size(max = 20) String floor,
            @Size(max = 20) String entrance,
            @Size(max = 500) String addressNotes,
            @Size(max = 255) String placeId,
            @Size(max = 500) String formattedAddress,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }
}
