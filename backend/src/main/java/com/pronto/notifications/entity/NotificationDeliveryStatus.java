package com.pronto.notifications.entity;

/**
 * Mirrors {@code notifications.delivery_status}'s {@code CHECK} constraint (see
 * {@code docs/architecture/data-model.md} §2.10). Covers the send pipeline only — distinct
 * from {@code read_at}, which tracks in-app read/unread state independently.
 */
public enum NotificationDeliveryStatus {
    PENDING,
    SENT,
    FAILED,

    /**
     * {@code V53}. The row was never eligible for delivery on its channel — as opposed to
     * {@link #FAILED}, which means a send was attempted and something went wrong.
     *
     * <p>Only {@code EmailDispatchJob} writes it, and only for rows whose {@code messageType} is
     * absent from {@link com.pronto.notifications.service.NotificationEmailCopy}'s allowlist.
     * Since {@code V53} those rows are not created on the {@code EMAIL} channel in the first
     * place, so in practice this value belongs to rows written before it — see the migration for
     * why they could neither be left {@code PENDING} nor honestly called {@code FAILED}.
     */
    SUPPRESSED
}
