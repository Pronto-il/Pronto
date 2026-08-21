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
        Instant readAt,
        Instant createdAt
) {
}
