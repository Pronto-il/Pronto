package com.pronto.matching;

/**
 * The customer-supplied service address for a booking-listing request (or order creation),
 * used purely as an input to {@link DistanceEtaStrategy} — never persisted by this package
 * (the {@code orders.service_*} columns owned by {@code bookings}/{@code Order} are the
 * actual persisted snapshot, per the approved design §1 classification item 5).
 */
public record ServiceLocation(String city, String street, String houseNumber, String apartment) {
}
