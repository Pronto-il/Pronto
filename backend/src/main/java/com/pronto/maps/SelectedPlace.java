package com.pronto.maps;

/**
 * A real place the customer <em>chose</em>, as opposed to an address they merely typed.
 *
 * <p><b>Why this type exists at all.</b> {@link PostalAddress} answers "what text will be
 * geocoded?" and {@link GeoCoordinates} answers "where is it?". Neither answers the question
 * address validation actually turns on: <em>did a human pick this from a list of places that
 * exist?</em> A geocoder will happily attempt any string, and a customer will happily invent a
 * house number — but a {@link #placeId() place id} only exists because a mapping provider
 * returned that place as a suggestion and somebody selected it. That is the distinction, and
 * giving it its own type is what stops it from decaying into "there was a non-empty string in
 * the request somewhere".
 *
 * <p><b>Always constructed through {@code service.SelectedPlaceValidator}</b>, never directly
 * from request fields. The validator is where "the client sent something plausible" is decided
 * once, so that every write path — registration, the profile edit and order creation — enforces
 * the same rule rather than three slightly different ones.
 *
 * <p><b>What this is not.</b> It is not proof of identity, and it is not tamper-proof: the
 * fields arrive from a browser, so a client that bypasses the UI can submit the id and
 * coordinates of some other real place. That is a deliberate, bounded residual — the address is
 * the customer's own destination, so falsifying it misdirects nobody but themselves and their
 * professional, and it crosses no privilege boundary. What it does close is the case the
 * product actually suffers from: an address that does not exist, typed by mistake, arriving as
 * if it were real. See {@code SelectedPlaceValidator}'s Javadoc for the full threat statement.
 *
 * @param placeId          the provider's opaque identifier for the selected place. Stored, never
 *                         parsed for meaning.
 * @param formattedAddress the provider's own normalized single-line rendering. Stored
 *                         <b>beside</b> the customer's address text and never over it, for the
 *                         same reason {@link GeocodeResult} gives: the human-readable address
 *                         belongs to the customer and to the professional who has to find the
 *                         door.
 * @param coordinates      the selected place's position, already range-validated by its own
 *                         type
 */
public record SelectedPlace(String placeId, String formattedAddress, GeoCoordinates coordinates) {

    public SelectedPlace {
        if (placeId == null || placeId.isBlank()) {
            throw new IllegalArgumentException("A selected place requires a place id.");
        }
        if (coordinates == null) {
            throw new IllegalArgumentException("A selected place requires coordinates.");
        }
        placeId = placeId.trim();
        formattedAddress = formattedAddress == null || formattedAddress.isBlank()
                ? null : formattedAddress.trim();
    }
}
