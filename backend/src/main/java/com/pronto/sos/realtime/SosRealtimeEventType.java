package com.pronto.sos.realtime;

/**
 * The realtime wire vocabulary — deliberately its own enum, not a reuse of
 * {@code sos.entity.SosEventType}.
 *
 * <p><b>Why a second enum.</b> The persisted {@code SosEventType} answers "what happened to this
 * request", one row per occurrence. This one answers "what is this recipient being told", and the
 * two genuinely differ: one {@code OFFERS_SENT} history row becomes an {@code OFFERS_SENT} message
 * to the customer <em>and</em> N {@code SOS_OFFER_RECEIVED} messages to professionals, each with a
 * different payload; one {@code PROFESSIONAL_SELECTED} row becomes {@code PROFESSIONAL_SELECTED} to
 * the customer, {@code SOS_SELECTED} to the winner, and {@code SOS_NOT_SELECTED} to the runners-up.
 * Collapsing audience-specific messages into the audience-agnostic history enum would force the
 * frontend to re-derive "is this about me" from data it should not need to reason about.
 *
 * <p><b>On the {@code ACCEPTED} ambiguity.</b> The persisted {@code SosOfferStatus.ACCEPTED} means
 * "this professional said they are available", and {@code SELECTED}/{@code NOT_SELECTED} mean "the
 * customer chose / did not choose them" — so the domain model already keeps the two apart, and no
 * breaking rename was made (see {@code sos/README.md}). The word "accepted" is nonetheless
 * ambiguous in English, so this wire vocabulary avoids it entirely: a professional's positive
 * response is {@link #PROFESSIONAL_AVAILABLE}, and being awarded the job is {@link #SOS_SELECTED}.
 * Nothing a client ever sees says "accepted".
 */
public enum SosRealtimeEventType {

    // ---- customer-facing: the request's own progress ----

    /** The SOS request was created and is about to be matched. */
    SOS_CREATED,

    /** Matching started — the platform is finding professionals. */
    MATCHING_STARTED,

    /** Offers went out. Carries how many professionals were contacted, not who they are. */
    OFFERS_SENT,

    /**
     * A professional responded that they are available and willing to come.
     *
     * <p><b>This is not a job award.</b> It means one more candidate exists for the customer to
     * choose between; the job is awarded only at {@link #SOS_SELECTED}. Carries the running
     * {@code availableCandidateCount}.
     */
    PROFESSIONAL_AVAILABLE,

    /**
     * The customer widened the search ("סרוק שוב") and a further wave of offers went out.
     *
     * <p>Carries how many expansions have been used and how many remain, so a second session of
     * the same customer's — another tab, another device — sees the control's state change without
     * having to re-derive the platform's expansion policy. Deliberately does <b>not</b> carry a
     * radius or a distance: there is no real geographic data behind this expansion yet (see
     * {@code sos.service.SosSearchScope}), and a wire field is exactly how invented precision
     * ends up in a UI.
     */
    SEARCH_EXPANDED,

    /** The candidate shortlist changed and is worth re-fetching. */
    CANDIDATES_UPDATED,

    /** The customer's ~2-minute choosing window opened. Carries the backend-owned deadline. */
    CUSTOMER_SELECTION_STARTED,

    /** The customer's own confirmation that their selection landed. */
    PROFESSIONAL_SELECTED,

    /**
     * A professional revised the ETA they had committed to. Carries the offer, the professional
     * and the new figure.
     *
     * <p>Sent whether or not the customer has chosen yet — a candidate who says "12 minutes, not
     * 20" while still merely {@link #PROFESSIONAL_AVAILABLE} is changing the very thing the
     * customer is comparing on. This used to be emitted only after selection, and a pre-selection
     * revision went out as {@code PROFESSIONAL_AVAILABLE} instead, which told the customer another
     * candidate had appeared when none had.
     */
    ETA_UPDATED,

    // ---- professional-facing ----

    /** An SOS opportunity was dispatched to this professional. Sent to that professional only. */
    SOS_OFFER_RECEIVED,

    /**
     * Acknowledges this professional's own response back to their other sessions/devices. Purely a
     * self-ack — it tells them nothing they did not just do.
     */
    OFFER_RESPONSE_RECORDED,

    /**
     * "Your window on this one closed." Sent to the professional the lapsed offer belonged to,
     * and to nobody else.
     *
     * <p>Distinct from {@link #EXPIRED}, which means the whole request is over. This one is
     * survivable: the request may still be very much alive, with other professionals available
     * and the customer about to choose between them. It exists so an inbox can retire a dead card
     * on its own rather than leaving it up until the professional taps it and gets a {@code 410}.
     */
    SOS_OFFER_EXPIRED,

    /** "The customer chose you. This job is yours." Sent to the selected professional only. */
    SOS_SELECTED,

    /**
     * "The customer selected another professional." Sent <b>only</b> to professionals who had
     * positively responded as available and lost — never to those who never answered, who simply
     * expire.
     */
    SOS_NOT_SELECTED,

    // ---- shared operational lifecycle ----

    PROFESSIONAL_CONFIRMED,
    ON_THE_WAY,
    ARRIVED,
    COMPLETED,
    CANCELLED,
    EXPIRED,

    /** Matching found nobody eligible to ask. Customer-facing, distinct from {@link #EXPIRED}. */
    SOS_FAILED
}
