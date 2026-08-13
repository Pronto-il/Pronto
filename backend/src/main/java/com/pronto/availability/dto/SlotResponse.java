package com.pronto.availability.dto;

import java.time.Instant;

/**
 * Response shape for {@code POST /api/availability/slots} (§2.10) and each entry in
 * {@code GET /api/availability/slots/me}'s {@code slots} array (§2.11). See
 * {@code docs/architecture/api-contract-bookings.md} §2.10-2.11.
 */
public record SlotResponse(
        Long id,
        Long professionalId,
        Instant startTime,
        Instant endTime,
        boolean isAvailable,
        Instant createdAt
) {
}
