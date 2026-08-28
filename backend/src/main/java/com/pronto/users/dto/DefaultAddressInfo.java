package com.pronto.users.dto;

/**
 * The nested {@code defaultAddress} object in {@code GET /api/users/me}'s response for a
 * {@code CUSTOMER}-role caller with a saved default address. {@code null} for a
 * {@code PROFESSIONAL} caller (the {@code users.default_*} columns are always null for
 * that role), and also {@code null} for a {@code CUSTOMER} with no recorded default city
 * (pre-V20 accounts) — mirrors {@link ProfessionalInfo}'s "absent means no such object"
 * convention rather than returning a partially-empty shape.
 *
 * <p><b>{@code placeId} is how the client tells a validated address from a legacy one</b>
 * ({@code V55}). {@code null} means this address predates address autocomplete and nobody ever
 * selected it from a list of real places. The client uses that to decide what to prefill: a
 * legacy address is still shown, and is still usable for booking exactly as before, but the
 * profile screen starts its address field unresolved so that saving requires a real selection.
 * Carrying it is what keeps that decision on one honest field rather than on the client guessing
 * from whether coordinates happen to be present.
 *
 * <p><b>Deliberately no latitude/longitude.</b> The client has no use for them — a booking to the
 * saved default address is resolved server-side from the {@code users} row, and a one-off address
 * carries its own coordinates from the selection the customer just made. Adding a position-shaped
 * field to a customer-facing DTO for no reason is how {@code maps.CustomerLocationPrivacyTest}'s
 * whole class of problem starts, even when this particular pair would be the caller's own.
 */
public record DefaultAddressInfo(
        String city,
        String street,
        String houseNumber,
        String apartment,
        String floor,
        String entrance,
        String addressNotes,
        String placeId,
        String formattedAddress
) {
}
