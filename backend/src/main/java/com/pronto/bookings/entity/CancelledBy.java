package com.pronto.bookings.entity;

/**
 * Mirrors {@code orders.cancelled_by}'s {@code CHECK} constraint (unchanged by any
 * Milestone 3 migration). Only set when {@code order_status} becomes {@code CANCELLED} —
 * left {@code NULL} for {@code REJECTED}, which is unambiguous from the status value alone.
 * See {@code docs/architecture/data-model.md} §2.9/§3 item 10.
 *
 * <p>{@code SYSTEM} is not producible by any endpoint in this milestone (§2.7's "not built
 * this milestone" note) — kept so the enum matches the full DB constraint for a future
 * automated process.
 */
public enum CancelledBy {
    CUSTOMER,
    PROFESSIONAL,
    SYSTEM
}
