package com.pronto.bookings.dto;

import com.pronto.bookings.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One entry in {@code GET /api/bookings/orders/me}'s {@code orders} array. Field set is
 * deliberately lean (list is lean, detail is rich — use §2.8 for full detail on one order).
 * See {@code docs/architecture/api-contract-bookings.md} §2.9.
 *
 * <p>{@code professionalId}/{@code professionalName} are an additive extension of that §2.9
 * shape: the customer's own orders list rendered rows with a date, a price and a status but never
 * said *who* the order was with, so the professional could not be identified — let alone opened —
 * without navigating into each order. Both values are already on {@code orders} /
 * {@link com.pronto.bookings.dto.OrderDetailResponse}; this only stops the list from being the one
 * order surface that hides them. Resolved in one batch per request, not per row (see
 * {@code BookingsService#listMine}). {@code professionalName} is nullable for exactly the same
 * reason it is on the detail DTO — a professional/user row that can no longer be resolved.
 */
public record OrderSummaryResponse(
        Long id,
        Long issueId,
        Long professionalId,
        String professionalName,
        OrderStatus orderStatus,
        Instant bookedStart,
        Instant bookedEnd,
        Instant expectedArrivalAt,
        BigDecimal finalPrice,
        Instant createdAt,
        Instant updatedAt
) {
}
