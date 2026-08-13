package com.pronto.notifications.entity;

/**
 * Mirrors {@code notifications.message_type}'s {@code CHECK} constraint, as amended by
 * {@code V14__alter_notifications_message_type_add_rejected.sql} (the {@code ORDER_REJECTED}
 * gap-fix). See {@code docs/architecture/data-model.md} §2.10 and
 * {@code docs/architecture/api-contract-notifications.md} §4.1.
 *
 * <p>{@code bookings.service.BookingsService} imports this enum directly (one-directional
 * {@code bookings -> notifications} dependency, §4.1 of the contract doc) — {@code
 * ORDER_ON_THE_WAY}/{@code ORDER_COMPLETED} are not produced by any caller yet (Milestone 6
 * scope, §4.6) and {@code EMAIL_VERIFICATION} is not produced by this milestone either (§6
 * item 6) — both kept here only so the enum matches the full DB constraint.
 */
public enum NotificationMessageType {
    ORDER_CREATED,
    ORDER_CONFIRMED,
    ORDER_ON_THE_WAY,
    ORDER_COMPLETED,
    ORDER_CANCELLED,
    ORDER_REJECTED,
    ORDER_EXPIRED,
    EMAIL_VERIFICATION
}
