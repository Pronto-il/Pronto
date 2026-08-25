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
    /**
     * <b>Production MS2, {@code V51}.</b> The professional's arrival at the customer's address has
     * been verified by the backend against the order's destination snapshot. The Standard flow had
     * no equivalent before MS2 because it had no arrival step to announce; the SOS flow's
     * {@link #SOS_ARRIVED} has existed since {@code V35}.
     */
    ORDER_ARRIVED,
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
    SOS_NO_PROFESSIONALS,

    /**
     * <b>Production MS2, {@code V51}.</b> The SOS request could not be matched because the routing
     * provider could not be reached — not because nobody is available.
     *
     * <p>Its own value rather than a reuse of {@link #SOS_NO_PROFESSIONALS}, because the two are
     * different facts with different recoveries and the difference matters most to the person
     * least able to tolerate a wrong one. Before MS2, SOS could only fail one way: distance was a
     * string comparison and could not fail. Now that candidate distance comes from an external
     * provider, telling a customer with a burst pipe "no plumber is available" when the truth is
     * "we could not measure how far away the available plumbers are" would be both false and
     * actively harmful.
     */
    SOS_TEMPORARILY_UNAVAILABLE
}
