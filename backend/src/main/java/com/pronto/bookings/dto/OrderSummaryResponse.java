package com.pronto.bookings.dto;

import com.pronto.bookings.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One entry in {@code GET /api/bookings/orders/me}'s {@code orders} array. Field set is
 * deliberately lean (list is lean, detail is rich — use §2.8 for full detail on one order).
 * See {@code docs/architecture/api-contract-bookings.md} §2.9.
 */
public record OrderSummaryResponse(
        Long id,
        Long issueId,
        OrderStatus orderStatus,
        Instant bookedStart,
        Instant bookedEnd,
        Instant expectedArrivalAt,
        BigDecimal finalPrice,
        Instant createdAt,
        Instant updatedAt
) {
}
