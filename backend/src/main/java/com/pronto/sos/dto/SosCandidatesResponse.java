package com.pronto.sos.dto;

import com.pronto.sos.entity.SosRequestStatus;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/sos/requests/{id}/candidates}.
 *
 * <p>{@code candidates} is capped at {@code pronto.sos.target-candidate-count} (3 by default)
 * and may legitimately be shorter, or empty while the request is still gathering responses —
 * the product direction is "up to 3", never "exactly 3", and a client must not assume otherwise.
 *
 * <p>{@code selectionExpiresAt} is repeated here (it is also on {@code SosRequestResponse}) so
 * the screen that renders the choice has the deadline it must count down to without a second
 * request. It is {@code null} until the selection window opens.
 */
public record SosCandidatesResponse(
        Long sosRequestId,
        SosRequestStatus status,
        Instant selectionExpiresAt,
        boolean selectionOpen,
        List<SosCandidate> candidates
) {
}
