package com.pronto.availability.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalTime;

/**
 * One weekday's configured working-hours row, shared by {@code GET}/{@code PUT
 * /api/availability/working-hours}'s response and by {@code GET /api/availability/calendar}'s
 * {@code workingHours} array. See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §4.1/§4.2/§4.6.
 * {@code startTime}/{@code endTime} are {@code null} when {@code enabled = false}.
 *
 * <p>{@code @JsonFormat(pattern = "HH:mm")} pins the wire format to exactly match every
 * {@code "startTime": "08:00"}-style example in the design doc -- without it, Jackson's
 * default {@code LocalTime} serializer always includes seconds (e.g. {@code "08:00:00"}),
 * which was verified live against a running instance during this milestone's manual
 * verification pass and corrected here rather than left silently mismatched from the
 * documented contract.
 */
public record WorkingHoursItem(
        int weekday,
        boolean enabled,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime
) {
}
