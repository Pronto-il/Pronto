package com.pronto.sos.dto;

import com.pronto.sos.entity.SosRequestStatus;

import java.util.List;

/** {@code GET /api/sos/requests/{id}/events} — the full chronological history, oldest first. */
public record SosTimelineResponse(
        Long sosRequestId,
        SosRequestStatus status,
        List<SosEventResponse> events
) {
}
