package com.pronto.professionals.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response of {@code PUT}/{@code GET /api/professionals/me/location} — <b>the professional's own
 * view of their own position state</b>, and the only endpoint in this API that returns anything
 * about a live position at all.
 *
 * <p><b>No coordinates.</b> Not because the caller may not have them — they own them, and they
 * just sent them — but because returning them would buy nothing (the client already knows where
 * it is) while creating a response shape carrying raw GPS that a later careless change could
 * widen. The useful facts are whether the platform currently considers them routable and, when
 * not, why, so that the professional app can say "המיקום שלך לא עדכני" instead of failing
 * mysteriously at the moment an SOS offer does not arrive.
 *
 * @param usable   whether this position currently satisfies
 *                 {@code pronto.location.professional-freshness} and
 *                 {@code pronto.location.max-accuracy-meters}
 * @param reason   why not, as a {@code maps.RouteUnavailableReason} name, or {@code null} when
 *                 {@code usable}. A stable code the frontend branches on, following the same
 *                 convention as {@code common.exception.ErrorCode}.
 * @param staleAfterSeconds how long from {@code updatedAt} this reading stays usable —
 *                 the configured freshness window, exposed so the client can schedule its next
 *                 refresh from the server's rule rather than hardcoding a duplicate of it
 */
public record ProfessionalLocationStatusResponse(
        boolean usable,
        Instant updatedAt,
        BigDecimal accuracyMeters,
        String reason,
        long staleAfterSeconds
) {
}
