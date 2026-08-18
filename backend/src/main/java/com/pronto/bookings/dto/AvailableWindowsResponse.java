package com.pronto.bookings.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/bookings/professionals/{professionalId}/available-windows
 * ?issueId=} — replaces the retired {@code GET .../slots?issueId=} entirely (not kept for
 * backward compatibility), per the professional weekly availability calendar design §9.2.2. An
 * empty {@code windows} array is a valid, expected response — not an error (unchanged semantics
 * from the old "empty {@code slots} array" case).
 *
 * <p>{@code defaultDurationMinutes}/{@code timezone} are echoed from the server rather than
 * hardcoded client-side — the same single-source-of-truth reasoning
 * {@code availability.dto.CalendarResponse}'s own {@code timezone} field already uses.
 */
public record AvailableWindowsResponse(
        Long professionalId,
        Long issueId,
        int defaultDurationMinutes,
        String timezone,
        List<AvailableWindow> windows
) {
}
