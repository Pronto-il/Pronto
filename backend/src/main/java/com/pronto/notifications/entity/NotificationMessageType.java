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
    EMAIL_VERIFICATION,

    // Pronto SOS (V35). Produced by sos.service.*, carried on a notifications row whose
    // subject is related_sos_request_id rather than related_order_id -- see Notification's
    // Javadoc. Named for what the recipient sees: SOS_OFFER_RECEIVED/SOS_OFFER_EXPIRED/
    // SOS_NOT_SELECTED go to professionals, the rest to the customer.
    SOS_OFFER_RECEIVED,
    SOS_OFFER_EXPIRED,
    SOS_CANDIDATES_READY,
    SOS_NOT_SELECTED,
    SOS_PROFESSIONAL_SELECTED,
    SOS_PROFESSIONAL_CONFIRMED,
    SOS_ON_THE_WAY,
    SOS_ARRIVED,
    SOS_COMPLETED,
    SOS_CANCELLED,
    SOS_EXPIRED,
    SOS_NO_PROFESSIONALS
}
