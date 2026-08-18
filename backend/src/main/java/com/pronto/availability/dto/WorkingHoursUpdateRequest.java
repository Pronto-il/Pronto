package com.pronto.availability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code PUT /api/availability/working-hours}'s request shape -- a full replace of the
 * caller's entire week in one call (idempotent upsert of all 7 weekdays, transactional). See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §4.2.
 */
public record WorkingHoursUpdateRequest(
        @NotNull @Size(min = 7, max = 7) List<@Valid WorkingHoursItemRequest> workingHours
) {
}
