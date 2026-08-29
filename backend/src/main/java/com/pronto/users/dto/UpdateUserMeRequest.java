package com.pronto.users.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/users/me} — {@code CUSTOMER}-only. See
 * {@code docs/architecture/api-contract.md} §2.6 and
 * {@code docs/architecture/product-ms10-profile-redesign-design.md} §4.2.
 *
 * <p>{@code fullName}/{@code phone} mirror the same {@code @NotBlank}/{@code @Size}
 * constraints as {@code auth.dto.RegisterRequest} (there is no "leave phone unset" state for a
 * {@code CUSTOMER} today, so this update endpoint doesn't introduce one).
 *
 * <p><b>{@code defaultAddress} is optional as of the address-flow redesign.</b> Omitting it
 * leaves the saved home address exactly as it was — this endpoint has no way to express "clear
 * my address", and did not before either. It became optional because registration no longer
 * collects an address at all: a customer can now legitimately have none, and requiring one here
 * would mean such a customer could not correct a typo in their own name without first inventing
 * a home address.
 *
 * <p>A <em>supplied</em> address is unchanged: still required in full (no partial-address
 * update), still must carry a selected place. See {@link CustomerAddressRequest}, which is also
 * the whole body of {@code PUT /api/users/me/default-address}.
 */
public record UpdateUserMeRequest(
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Size(max = 20) String phone,
        @Valid CustomerAddressRequest defaultAddress
) {
}
