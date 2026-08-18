package com.pronto.availability.dto;

import java.time.Instant;

/**
 * Response shape for {@code POST}/{@code PATCH /api/availability/blocks*} -- mirrors {@code
 * SlotResponse}'s existing shape (includes {@code professionalId} and both timestamps). See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §4.3/§4.4.
 */
public record BlockResponse(
        Long id,
        Long professionalId,
        Instant startAt,
        Instant endAt,
        String reason,
        Instant createdAt,
        Instant updatedAt
) {
}
