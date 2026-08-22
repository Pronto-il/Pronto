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
 * <p>All types except {@link #PROFESSIONAL_RESPONDED}, {@link #OFFER_VIEWED},
 * {@link #OFFER_EXPIRED}, {@link #ETA_UPDATED} and {@link #SEARCH_EXPANDED} occur at most once
 * per request, enforced by the {@code ux_sos_events_singleton} partial unique index. The first
 * four are per-offer rather than per-request (one request fans out to many offers); the last is
 * per-request but legitimately repeatable, since expanding the search twice is the whole point of
 * the control that produces it.
 */
public enum SosEventType {

    SOS_CREATED,
    MATCHING_STARTED,
    OFFERS_SENT,

    /** Repeatable — one per recipient who opens the offer. */
    OFFER_VIEWED,

    /**
     * Repeatable — one per offer that lapsed unanswered at its own {@code expires_at}.
     *
     * <p>Deliberately distinct from {@link #EXPIRED}, and the distinction is the whole point:
     * this is <em>one professional</em> running out of time on <em>one card</em>, while the
     * request carries on with whoever else is still answering. {@link #EXPIRED} is the request
     * itself terminating, which is everybody's business. Only the professional whose offer it
     * was is told about this one — the customer's view of dispatch stays aggregate
     * ("2 available"), never "professional X ignored you".
     */
    OFFER_EXPIRED,

    /** Repeatable — one per professional who accepts or rejects. */
    PROFESSIONAL_RESPONDED,

    /**
     * Repeatable — a professional revised the ETA they had already committed to.
     *
     * <p>Deliberately distinct from {@link #PROFESSIONAL_RESPONDED}, which it used to be recorded
     * as. Three different things were sharing that one type — "I'm available", "I decline" and
     * "make that 12 minutes, not 20" — which left the realtime publisher inferring which had
     * happened from the offer's current status. That inference is wrong for the case that matters
     * most: a revision on an {@code ACCEPTED} offer is indistinguishable from a fresh acceptance,
     * so the customer was told {@code PROFESSIONAL_AVAILABLE} ("one more candidate for you") when
     * the truth was {@code ETA_UPDATED} ("the one you're looking at will be here sooner"). Same
     * refetch, entirely different thing to say — and with candidate cards animating in on arrival,
     * the wrong one makes an existing card re-announce itself on every edit.
     */
    ETA_UPDATED,

    /**
     * Repeatable — the customer pressed "סרוק שוב" and the search widened on this same request.
     *
     * <p>Bounded by {@code pronto.sos.max-search-expansions}, and exempt from
     * {@code ux_sos_events_singleton} ({@code V39}) precisely because repeating it is the
     * feature: expansion step 1, then step 2. The {@code detail} carries the new scope level and
     * how many additional professionals were contacted, so the timeline can explain a second wave
     * of offers that would otherwise appear out of nowhere.
     */
    SEARCH_EXPANDED,

    /** The first professional accepted; the customer may choose from here on. */
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
