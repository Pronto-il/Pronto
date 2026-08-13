package com.pronto.bookings.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/bookings/professionals/{professionalId}/slots?issueId=}.
 * See {@code docs/architecture/api-contract-bookings.md} §2.3. An empty {@code slots} array
 * is a valid, expected response — not an error.
 */
public record SlotListingResponse(Long professionalId, List<SlotSummary> slots) {
}
