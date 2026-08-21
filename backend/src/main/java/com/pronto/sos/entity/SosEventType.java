package com.pronto.sos.entity;

/**
 * Mirrors {@code sos_events.event_type}'s {@code CHECK} constraint ({@code V34}).
 *
 * <p>This is the vocabulary the realtime layer will publish over WebSockets in the next
 * phase. Every business transition in {@code sos.service} records one of these in the same
 * transaction as the state change itself, so the log can never disagree with the request's
 * status — and a realtime publisher added later only has to forward what is already being
 * written, with no business logic moved or rewritten.
 *
 * <p>All types except {@link #PROFESSIONAL_RESPONDED} and {@link #OFFER_VIEWED} occur at most
 * once per request, enforced by the {@code ux_sos_events_singleton} partial unique index.
 */
public enum SosEventType {

    SOS_CREATED,
    MATCHING_STARTED,
    OFFERS_SENT,

    /** Repeatable — one per recipient who opens the offer. */
    OFFER_VIEWED,

    /** Repeatable — one per professional who accepts or rejects. */
    PROFESSIONAL_RESPONDED,

    /** Enough professionals accepted; the candidate shortlist is assembled. */
    CANDIDATES_READY,

    /** The customer's ~2-minute selection window opened. */
    CUSTOMER_SELECTION_STARTED,

    PROFESSIONAL_SELECTED,
    PROFESSIONAL_CONFIRMED,
    ON_THE_WAY,
    ARRIVED,
    COMPLETED,
    CANCELLED,
    EXPIRED,

    /** Matching found nobody eligible. */
    FAILED
}
