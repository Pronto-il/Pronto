package com.pronto.availability.dto;

import com.pronto.bookings.entity.OrderStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * One entry in {@code GET /api/availability/calendar}'s {@code segments} array. See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §3/§4.6/§5.
 *
 * <p>{@code blockId}/{@code reason} are populated only when {@code type == BLOCKED};
 * {@code orderId}/{@code orderStatus} only when {@code type == BOOKED} -- the three static
 * factory methods below are the only way to construct one, so an {@code AVAILABLE} segment
 * can never accidentally carry a stray {@code blockId}/{@code orderId}. Directly reuses
 * {@code bookings.entity.OrderStatus} (not a locally-duplicated copy) -- the same
 * cross-package DTO-field reuse convention {@code bookings.dto.OrderResponse} itself already
 * uses.
 *
 * <p>Exact, non-grid-rounded timestamps (design §5's "grid precision" note) -- the 30-minute
 * grid is a frontend rendering/interaction convention only, never applied to this data.
 */
public record CalendarSegment(
        SegmentType type,
        Instant startAt,
        Instant endAt,
        Long blockId,
        String reason,
        Long orderId,
        OrderStatus orderStatus
) {

    public static CalendarSegment available(Instant startAt, Instant endAt) {
        return new CalendarSegment(SegmentType.AVAILABLE, startAt, endAt, null, null, null, null);
    }

    public static CalendarSegment blocked(Instant startAt, Instant endAt, Long blockId, String reason) {
        return new CalendarSegment(SegmentType.BLOCKED, startAt, endAt, blockId, reason, null, null);
    }

    public static CalendarSegment booked(Instant startAt, Instant endAt, Long orderId, OrderStatus orderStatus) {
        return new CalendarSegment(SegmentType.BOOKED, startAt, endAt, null, null, orderId, orderStatus);
    }

    /**
     * §9.2.2 of the professional weekly availability calendar design:
     * {@code deriveAvailableWindows}'s own filter criterion (
     * {@code segment.duration() >= minDuration}) reads this method directly.
     */
    public Duration duration() {
        return Duration.between(startAt, endAt);
    }
}
