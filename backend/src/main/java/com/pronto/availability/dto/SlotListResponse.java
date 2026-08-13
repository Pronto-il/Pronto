package com.pronto.availability.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/availability/slots/me}. See
 * {@code docs/architecture/api-contract-bookings.md} §2.11. An empty {@code slots} array is
 * a valid, expected response — not an error.
 */
public record SlotListResponse(List<SlotListItem> slots) {
}
