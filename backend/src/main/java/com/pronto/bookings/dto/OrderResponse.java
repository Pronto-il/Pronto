package com.pronto.bookings.dto;

import com.pronto.bookings.entity.CancelledBy;
import com.pronto.bookings.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response shape shared by {@code POST /api/bookings/orders} (§2.4, {@code 201}),
 * {@code POST .../accept} (§2.5), and {@code POST .../reject} (§2.6) — all three return the
 * order in this same shape, differing only in {@code orderStatus}/{@code cancelledBy}. See
 * {@code docs/architecture/api-contract-bookings.md} §2.4-2.6.
 */
public record OrderResponse(
        Long id,
        Long issueId,
        Long customerId,
        Long professionalId,
        OrderStatus orderStatus,
        Instant bookedStart,
        Instant bookedEnd,
        BigDecimal finalPrice,
        CancelledBy cancelledBy,
        Instant createdAt,
        Instant updatedAt
) {
}
