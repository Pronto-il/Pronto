package com.pronto.bookings.entity;

/**
 * Mirrors {@code orders.order_status}'s {@code CHECK} constraint, as amended by
 * {@code V11__alter_orders_status_add_rejected.sql} (the genuine 7th value, {@code REJECTED},
 * per {@code docs/architecture/data-model.md} §2.9/§3 item 10). See
 * {@code docs/architecture/api-contract-bookings.md} §2.4-2.9 for which endpoints in this
 * milestone reach which values — only {@code PENDING}, {@code CONFIRMED}, {@code REJECTED},
 * and {@code CANCELLED} are ever produced by Milestone 3; {@code ON_THE_WAY}/
 * {@code COMPLETED} are Milestone 6 scope and {@code EXPIRED} is Milestone 5's sweep job,
 * both kept here only so the enum matches the full DB constraint.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    ON_THE_WAY,
    COMPLETED,
    CANCELLED,
    REJECTED,
    EXPIRED
}
