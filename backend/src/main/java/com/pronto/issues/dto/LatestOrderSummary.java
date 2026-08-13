package com.pronto.issues.dto;

import com.pronto.bookings.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The {@code latestOrder} field in {@code GET /api/issues/{id}}'s response — the
 * most-recently-created {@code orders} row for this issue, regardless of status ({@code null}
 * if none exists yet). See {@code docs/architecture/api-contract-bookings.md} §2.1.
 *
 * <p>This is the one place {@code issues} depends on {@code bookings} (a small, deliberate
 * exception to the otherwise one-directional {@code bookings -> issues} dependency) — see
 * {@code docs/architecture/api-contract-bookings.md} §2.1's "why this belongs here" note for
 * the justification.
 */
public record LatestOrderSummary(
        Long id,
        Long professionalId,
        String professionalName,
        OrderStatus orderStatus,
        Instant bookedStart,
        Instant bookedEnd,
        BigDecimal finalPrice,
        Instant createdAt
) {
}
