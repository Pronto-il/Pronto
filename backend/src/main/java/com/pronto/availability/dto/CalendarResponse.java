package com.pronto.availability.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/availability/calendar}'s response shape. See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §4.6.
 *
 * <p>{@code workingHours} is date-independent (returned once, not per-day) so the frontend
 * can shade the "outside working hours" background across the whole visible week without
 * per-day lookups. {@code timezone} is always the fixed business-timezone constant's zone id
 * ({@code "Asia/Jerusalem"}) -- echoed explicitly so the frontend never hardcodes/guesses it
 * (single source of truth, same reasoning as {@code bookings}'s planned
 * {@code defaultDurationMinutes} echo, M2).
 */
public record CalendarResponse(
        Long professionalId,
        Instant from,
        Instant to,
        String timezone,
        List<WorkingHoursItem> workingHours,
        List<CalendarSegment> segments
) {
}
