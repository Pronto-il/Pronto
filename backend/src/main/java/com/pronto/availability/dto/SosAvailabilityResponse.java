package com.pronto.availability.dto;

import java.time.Instant;

/**
 * Response shape shared by {@code PUT /api/availability/sos-availability} (§2.14) and
 * {@code GET /api/availability/sos-availability} (§2.15). See
 * {@code docs/architecture/api-contract-bookings.md} §2.14-2.15.
 */
public record SosAvailabilityResponse(
        Long professionalId,
        boolean isAvailable,
        Instant updatedAt
) {
}
