package com.pronto.availability.dto;

import java.time.Instant;

/**
 * One entry in {@code GET /api/availability/slots/me}'s {@code slots} array (§2.11).
 * Deliberately omits {@code professionalId} — unlike {@link SlotResponse} (§2.10's create
 * response), the contract's §2.11 example response doesn't include it, since every entry is
 * implicitly the caller's own. See {@code docs/architecture/api-contract-bookings.md} §2.11.
 */
public record SlotListItem(
        Long id,
        Instant startTime,
        Instant endTime,
        boolean isAvailable,
        Instant createdAt
) {
}
