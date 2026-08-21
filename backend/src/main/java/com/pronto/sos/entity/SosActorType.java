package com.pronto.sos.entity;

/**
 * Who caused an {@code sos_events} row, and who cancelled an {@code sos_requests} row
 * ({@code sos_requests.cancelled_by}). Mirrors both {@code CHECK} constraints in {@code V34}.
 *
 * <p>Deliberately a separate enum from {@code bookings.entity.CancelledBy} despite the
 * identical three constants: that one is scoped to "who cancelled an order", this one also
 * labels non-cancellation events, and {@code sos} importing a {@code bookings} enum for its
 * own audit log would couple the two for no gain.
 */
public enum SosActorType {

    CUSTOMER,
    PROFESSIONAL,

    /** Background sweeps and platform-driven transitions (matching, dispatch, expiry). */
    SYSTEM
}
