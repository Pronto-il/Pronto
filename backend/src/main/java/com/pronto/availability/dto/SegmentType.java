package com.pronto.availability.dto;

/**
 * The three visual states {@code GET /api/availability/calendar} renders, per the product
 * spec's §9 (at least three states, not four -- see the design doc's §9.4 for why a
 * {@code PENDING} order is rendered as {@code BOOKED}, sub-labeled by {@code orderStatus},
 * rather than inventing a fourth top-level state). See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §3/§4.6.
 */
public enum SegmentType {
    AVAILABLE,
    BLOCKED,
    BOOKED
}
