package com.pronto.matching;

import com.pronto.maps.PostalAddress;

/**
 * The customer-supplied service address for a booking-listing request (or order creation), as
 * human-readable text.
 *
 * <p><b>MS2 narrowed what this is for.</b> It used to be the direct input to
 * {@link DistanceEtaStrategy}, whose implementation compared its {@code city} field against a
 * professional's city string — which is how the platform came to answer a geographic question
 * with string equality. It is no longer a routing input at all: routing takes coordinates, and
 * the bridge between the two is {@link #toPostalAddress()}, resolved <b>once, on write</b> by
 * {@code maps.service.ServiceAddressGeocoder}, not once per professional card.
 *
 * <p>The text itself remains first-class and is never replaced by a geocoder's rendering of it —
 * a professional has to read it to find the door, and an order's address must stay exactly what
 * was agreed. See {@code V50}'s header.
 */
public record ServiceLocation(String city, String street, String houseNumber, String apartment) {

    /**
     * The building-locating subset, for geocoding.
     *
     * <p>{@link #apartment} is deliberately dropped: no geocoder resolves "דירה 4", it can push a
     * query off the correct building, and including it would mean a customer correcting their
     * apartment number triggers a fresh paid geocode of an address that has not moved. See
     * {@link PostalAddress}.
     */
    public PostalAddress toPostalAddress() {
        return new PostalAddress(city, street, houseNumber);
    }
}
