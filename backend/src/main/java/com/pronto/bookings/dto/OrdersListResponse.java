package com.pronto.bookings.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/bookings/orders/me}. See
 * {@code docs/architecture/api-contract-bookings.md} §2.9.
 */
public record OrdersListResponse(List<OrderSummaryResponse> orders) {
}
