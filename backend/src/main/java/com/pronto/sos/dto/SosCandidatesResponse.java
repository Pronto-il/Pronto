package com.pronto.sos.dto;

import com.pronto.sos.entity.SosRequestStatus;

import java.util.List;

/**
 * {@code GET /api/sos/requests/{id}/candidates}.
 *
 * <p>{@code candidates} is every professional who has accepted — uncapped since MS3, and
 * legitimately empty while the request is still gathering responses.
 *
 * <p><b>There is no deadline field.</b> {@code selectionExpiresAt} used to live here so the
 * screen could count down to it; the customer's decision deadline was removed in the MS3
 * follow-up, and a client that still wants to know whether choosing is possible reads
 * {@code selectionOpen}, which is derived from the request's status alone.
 */
public record SosCandidatesResponse(
        Long sosRequestId,
        SosRequestStatus status,
        boolean selectionOpen,
        List<SosCandidate> candidates
) {
}
