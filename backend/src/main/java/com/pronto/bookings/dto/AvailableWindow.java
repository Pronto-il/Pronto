package com.pronto.bookings.dto;

import java.time.Instant;

/**
 * One entry in {@code GET /api/bookings/professionals/{professionalId}/available-windows
 * ?issueId=}'s {@code windows} array. See the professional weekly availability calendar
 * design §9.2.2. Every window's duration is guaranteed {@code >=
 * AvailableWindowsResponse#defaultDurationMinutes} — shorter windows are dropped entirely by
 * {@code AvailabilityDerivationService#deriveAvailableWindows}'s own filter, never returned as
 * an unusable ghost entry.
 */
public record AvailableWindow(Instant startAt, Instant endAt) {
}
