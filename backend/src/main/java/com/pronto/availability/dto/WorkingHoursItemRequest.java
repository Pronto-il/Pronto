package com.pronto.availability.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

/**
 * One entry of {@code PUT /api/availability/working-hours}'s request array. See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §4.2.
 *
 * <p>Bean Validation only covers what's expressible per-field ({@code weekday} in range,
 * {@code enabled} present) -- "exactly 7 entries, one per weekday 0-6, no duplicates/gaps"
 * and "{@code startTime}/{@code endTime} required and {@code endTime > startTime} when
 * {@code enabled = true}" are cross-field/cross-entry rules, validated in
 * {@code AvailabilityService#updateWorkingHours}, same split this codebase already applies
 * to {@code CreateSlotRequest}'s "strictly future"/"endTime > startTime" rules.
 */
public record WorkingHoursItemRequest(
        @NotNull @Min(0) @Max(6) Integer weekday,
        @NotNull Boolean enabled,
        LocalTime startTime,
        LocalTime endTime
) {
}
