package com.pronto.sos.dto;

import com.pronto.sos.entity.SosRequestStatus;

import java.util.List;

/**
 * {@code GET /api/sos/requests/{id}/candidates}.
 *
 * <p>{@code candidates} is every professional the request is still live with: those who have
 * accepted, and those who have been asked and whose response window is still open. Each carries a
 * {@link SosCandidateState} saying which. Uncapped since MS3, and legitimately empty — before any
 * offer has been dispatched, and after every one of them has lapsed or been declined.
 *
 * <p><b>{@code selectionOpen} is not "the list is non-empty".</b> It follows the request's status,
 * which opens on the first genuine acceptance, so a list consisting entirely of
 * {@link SosCandidateState#REQUESTED} candidates comes back with {@code selectionOpen = false} —
 * there are people on screen and nothing yet to choose between. Any client that infers
 * selectability from the presence of a candidate would offer a button the backend refuses.
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
