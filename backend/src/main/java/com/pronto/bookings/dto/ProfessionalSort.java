package com.pronto.bookings.dto;

/**
 * {@code sort} query param on {@code GET /api/bookings/professionals}/
 * {@code .../sos-professionals}. {@code CHEAPEST} (default) leaves the DB's
 * {@code ORDER BY base_price ASC} order untouched; {@code FASTEST} re-sorts the fetched list
 * in-memory by computed {@code etaMinutes} ascending. See
 * {@code bookings.service.BookingsService}.
 */
public enum ProfessionalSort {
    CHEAPEST,
    FASTEST
}
