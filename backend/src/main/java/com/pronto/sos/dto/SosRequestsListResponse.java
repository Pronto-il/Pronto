package com.pronto.sos.dto;

import java.util.List;

/** {@code GET /api/sos/requests/me} — the caller's own SOS requests, newest first. */
public record SosRequestsListResponse(
        List<SosRequestResponse> requests
) {
}
