package com.pronto.availability.dto;

import java.util.List;

/**
 * {@code GET}/{@code PUT /api/availability/working-hours}'s shared response shape. See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §4.1/§4.2. {@code
 * workingHours} may have fewer than 7 entries only before first-time setup completes (a
 * brand-new professional) -- not an error, the frontend renders the first-time setup flow in
 * that case.
 */
public record WorkingHoursListResponse(List<WorkingHoursItem> workingHours) {
}
