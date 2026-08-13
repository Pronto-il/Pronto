package com.pronto.notifications.entity;

/**
 * Mirrors {@code notifications.delivery_status}'s {@code CHECK} constraint (see
 * {@code docs/architecture/data-model.md} §2.10). Covers the send pipeline only — distinct
 * from {@code read_at}, which tracks in-app read/unread state independently.
 */
public enum NotificationDeliveryStatus {
    PENDING,
    SENT,
    FAILED
}
