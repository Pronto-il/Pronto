package com.pronto.bookings.dto;

import java.time.Instant;
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
 * {@code earliestBookableAt}/{@code minLeadMinutes} are echoed for exactly that reason too — see
 * their own docs below.
 *
 * @param windows            the professional's real derived availability, <b>unfiltered by the
 *                           lead-time rule</b>. This is deliberate: the customer is entitled to see
 *                           that the professional's calendar is genuinely open at 11:30, and the
 *                           screen says only that a <em>Standard booking</em> cannot be made for it
 *                           — not that the person is busy. Filtering here would have made the two
 *                           indistinguishable.
 * @param earliestBookableAt the first instant a Standard booking may start
 *                           ({@code now + minLeadMinutes}), computed server-side at the moment this
 *                           response was built. The client renders start times before it as
 *                           unavailable; the server re-derives it at commit
 *                           ({@code BookingsService#createOrder}) and is the only authority — a
 *                           customer who leaves this screen open for an hour has this value go
 *                           stale, and that is fine precisely because nothing depends on it being
 *                           fresh.
 * @param minLeadMinutes     the rule itself, so the client can word the explanation
 *                           ("2.5 שעות מראש") without hardcoding a number the backend owns
 */
public record AvailableWindowsResponse(
        Long professionalId,
        Long issueId,
        int defaultDurationMinutes,
        String timezone,
        Instant earliestBookableAt,
        int minLeadMinutes,
        List<AvailableWindow> windows
) {
}
