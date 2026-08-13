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
 */
public record NotificationResponse(
        Long id,
        NotificationMessageType messageType,
        Long relatedOrderId,
        Instant readAt,
        Instant createdAt
) {
}
