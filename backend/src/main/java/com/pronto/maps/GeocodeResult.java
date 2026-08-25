package com.pronto.maps;

/**
 * What a {@link GeocodingProvider} answered.
 *
 * <p>A result is either {@link GeocodeStatus#RESOLVED} <em>with</em> coordinates, or one of the
 * non-resolved statuses <em>without</em> them — enforced in the constructor so no caller can
 * receive a "successful" result carrying nothing, or a failure carrying coordinates it might be
 * tempted to use.
 *
 * @param formattedAddress the provider's own normalized rendering of what it matched, kept for
 *                         diagnostics only. <b>Never written over the customer's own address
 *                         text</b> — the human-readable snapshot belongs to the customer and to
 *                         the professional who has to find the door, not to a geocoder's idea of
 *                         how it should be spelled.
 */
public record GeocodeResult(GeocodeStatus status, GeoCoordinates coordinates, String formattedAddress) {

    public GeocodeResult {
        if (status == null) {
            throw new IllegalArgumentException("A geocode result requires a status.");
        }
        if (status.isUsable() != (coordinates != null)) {
            throw new IllegalArgumentException(
                    "A RESOLVED geocode result must carry coordinates and a non-RESOLVED one must not; status="
                            + status + ", coordinates " + (coordinates == null ? "absent" : "present") + ".");
        }
    }

    public static GeocodeResult resolved(GeoCoordinates coordinates, String formattedAddress) {
        return new GeocodeResult(GeocodeStatus.RESOLVED, coordinates, formattedAddress);
    }

    /** The provider answered; the address is not resolvable. Do not retry this exact text. */
    public static GeocodeResult failed() {
        return new GeocodeResult(GeocodeStatus.FAILED, null, null);
    }

    /** The provider could not answer. Says nothing about the address; retry later. */
    public static GeocodeResult unavailable() {
        return new GeocodeResult(GeocodeStatus.UNAVAILABLE, null, null);
    }

    public boolean isResolved() {
        return status.isUsable();
    }
}
