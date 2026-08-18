package com.pronto.bookings.dto;

/**
 * {@code sort} query param on {@code GET /api/bookings/professionals}/
 * {@code .../sos-professionals}. {@code CHEAPEST} (default for both listing endpoints) leaves
 * the DB's {@code ORDER BY base_price ASC} order untouched; {@code RECOMMENDED} re-sorts by
 * {@code averageRating} descending (nulls last), then {@code reviewCount} descending;
 * {@code FASTEST} re-sorts by computed {@code etaMinutes} ascending. As of the MS3/MS4
 * product-corrections pass, the frontend only exposes {@code RECOMMENDED}/{@code CHEAPEST} as
 * selectable chips (identical 2-way toggle on both the Standard and SOS flows) — {@code
 * FASTEST} remains a valid value/ranking here, reachable via a direct API call, but is not
 * wired to any UI in either flow. See {@code bookings.service.BookingsService} and
 * {@code docs/architecture/ms3-ms4-corrections-design.md} §3.
 */
public enum ProfessionalSort {
    CHEAPEST,
    RECOMMENDED,
    FASTEST
}
