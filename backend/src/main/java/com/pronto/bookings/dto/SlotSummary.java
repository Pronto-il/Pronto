package com.pronto.bookings.dto;

import java.time.Instant;

/**
 * One entry in {@code GET /api/bookings/professionals/{professionalId}/slots?issueId=}'s
 * {@code slots} array. See {@code docs/architecture/api-contract-bookings.md} §2.3.
 */
public record SlotSummary(Long slotId, Instant startTime, Instant endTime) {
}
