package com.pronto.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A Customer's default address, nested under {@link CustomerRegistrationData}. See
 * {@code docs/architecture/api-contract.md} §2.1 (backend registration flow separation
 * task §4-6). {@code city}/{@code street}/{@code houseNumber} are required;
 * {@code apartment}/{@code floor}/{@code entrance}/{@code addressNotes} are optional.
 * {@code @Size} caps mirror the {@code users.default_*} column lengths (V20).
 *
 * <p><b>The last four fields identify the place the customer actually SELECTED</b> from address
 * autocomplete, rather than the text they typed ({@code V55}). They are required at registration
 * — a brand-new address has never been confirmed by anyone — and are validated together by
 * {@code maps.service.SelectedPlaceValidator}, which refuses a partial claim. Bean Validation
 * cannot express "all four or none", so they are individually optional here and enforced there,
 * the same presence-and-shape-here / rules-in-the-service-layer split the rest of this package
 * uses.
 *
 * <p>The city/street/house-number text is still sent and still stored: it is what a professional
 * reads to find the door, and {@code apartment}/{@code floor}/{@code entrance} have no
 * equivalent in any geocoder's answer.
 *
 * @param placeId          provider place id of the selected suggestion
 * @param formattedAddress the provider's own normalized rendering of that place
 * @param latitude         the selected place's position, stored directly rather than re-derived
 *                         by geocoding the text — see {@code AuthAccountWriter}, where supplying
 *                         it removes a provider call rather than adding one
 */
public record DefaultAddressRequest(
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
