package com.pronto.notifications.entity;

/**
 * Mirrors {@code notifications.channel}'s {@code CHECK} constraint (see
 * {@code docs/architecture/data-model.md} §2.10) — in-app + email only, no SMS/push (settled
 * decision, {@code docs/architecture/api-contract-notifications.md} §7).
 */
public enum NotificationChannel {
    IN_APP,
    EMAIL
}
