package com.pronto.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A Customer's default address, nested under {@link CustomerRegistrationData}. See
 * {@code docs/architecture/api-contract.md} §2.1 (backend registration flow separation
 * task §4-6). {@code city}/{@code street}/{@code houseNumber} are required;
 * {@code apartment}/{@code floor}/{@code entrance}/{@code addressNotes} are optional.
 * {@code @Size} caps mirror the {@code users.default_*} column lengths (V20).
 */
public record DefaultAddressRequest(
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 150) String street,
        @NotBlank @Size(max = 20) String houseNumber,
        @Size(max = 20) String apartment,
        @Size(max = 20) String floor,
        @Size(max = 20) String entrance,
        @Size(max = 500) String addressNotes
) {
}
