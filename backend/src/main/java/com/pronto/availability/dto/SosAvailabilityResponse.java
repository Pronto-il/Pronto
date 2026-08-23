package com.pronto.availability.dto;

import java.time.Instant;

/**
 * Response shape shared by {@code PUT /api/availability/sos-availability} (§2.14) and
 * {@code GET /api/availability/sos-availability} (§2.15). See
 * {@code docs/architecture/api-contract-bookings.md} §2.14-2.15.
 *
 * @param bookable MS1 (D-G): whether this professional is actually marketplace-eligible —
 *                 approved <em>and</em> onboarding complete
 *                 ({@link com.pronto.professionals.ProfessionalEligibility}). Independent of
 *                 {@code isAvailable}, which is only the professional's own intent. A
 *                 professional whose SOS toggle is on but who is {@code bookable = false} will
 *                 never be dispatched an offer, and the dashboard must say so rather than
 *                 rendering them as live.
 */
public record SosAvailabilityResponse(
        Long professionalId,
        boolean isAvailable,
        Instant updatedAt,
        boolean bookable
) {
}
