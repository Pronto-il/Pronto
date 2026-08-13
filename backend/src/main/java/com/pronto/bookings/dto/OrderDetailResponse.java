package com.pronto.bookings.dto;

import com.pronto.bookings.entity.CancelledBy;
import com.pronto.bookings.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response body for {@code GET /api/bookings/orders/{orderId}} — the tracking/status
 * endpoint, enriched with display-friendly names so the client needs no follow-up calls. See
 * {@code docs/architecture/api-contract-bookings.md} §2.8.
 */
public record OrderDetailResponse(
        Long id,
        Long issueId,
        Long customerId,
        String customerName,
        Long professionalId,
        String professionalName,
        OrderStatus orderStatus,
        Instant bookedStart,
        Instant bookedEnd,
        BigDecimal finalPrice,
        CancelledBy cancelledBy,
        Instant createdAt,
        Instant updatedAt
) {
}
