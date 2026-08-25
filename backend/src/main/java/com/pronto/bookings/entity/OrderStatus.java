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
    /**
     * <b>Production MS2, {@code V51}.</b> The professional is at the customer's address, and the
     * backend has verified it geographically — this value is only ever written after a
     * server-side proximity check against the order's immutable destination snapshot, never
     * because a client pressed a button. See {@code BookingsService#arrived}.
     *
     * <p>Sits between {@code ON_THE_WAY} and {@code COMPLETED} and is <b>optional</b>:
     * {@code ON_THE_WAY -> COMPLETED} remains legal, so a professional whose device has no usable
     * GPS, and any order already in flight when MS2 shipped, can still finish the job. Arrival is
     * a verification step, not a toll gate that can strand somebody mid-job.
     */
    ARRIVED,
    COMPLETED,
    CANCELLED,
    REJECTED,
    EXPIRED
}
