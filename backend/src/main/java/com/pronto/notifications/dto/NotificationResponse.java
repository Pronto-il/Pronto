package com.pronto.notifications.dto;

import com.pronto.notifications.entity.NotificationMessageType;

import java.time.Instant;

/**
 * Response shape shared by {@code GET /api/notifications}'s list items and
 * {@code POST /api/notifications/{id}/read} (§3.1/§3.2 of
 * {@code docs/architecture/api-contract-notifications.md}). {@code channel}/
 * {@code deliveryStatus} are deliberately not included (§3.1 — every row returned here is,
 * by construction, {@code channel = IN_APP}, and {@code deliveryStatus} carries no
 * information worth exposing).
 *
 * <p>{@code relatedSosRequestId} (Pronto SOS, {@code V35}) is the SOS counterpart of
 * {@code relatedOrderId}: exactly one of the two is non-null on any row, and the frontend
 * picks its deep-link target from whichever is set. Added rather than overloading
 * {@code relatedOrderId}, which is FK-constrained to {@code orders} and therefore cannot
 * carry an SOS request id.
 */
public record NotificationResponse(
        Long id,
        NotificationMessageType messageType,
        Long relatedOrderId,
        Long relatedSosRequestId,
        /**
         * The issue the SOS request in {@code relatedSosRequestId} was activated on, or
         * {@code null} for an order row (and for an SOS row whose request no longer resolves).
         *
         * <p><b>Derived, never stored.</b> There is no {@code related_issue_id} column: this is
         * read through {@code notifications.service.SosRequestIssueResolver} at response-assembly
         * time, in one batched lookup for the whole feed.
         *
         * <p>It exists because {@code relatedSosRequestId} alone was not enough to navigate. The
         * customer's live SOS screen is {@code /issues/{issueId}/sos-booking} — anchored to the
         * problem rather than to the attempt, since one issue accumulates many attempts — so the
         * bell had a subject it could not turn into a destination, and every customer-facing SOS
         * row was a dead end. Professional-facing SOS rows are unaffected: their destination is
         * the offer inbox, which needs no id at all.
         */
        Long relatedIssueId,
        Instant readAt,
        Instant createdAt
) {
}
