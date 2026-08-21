package com.pronto.sos.entity;

/**
 * Mirrors {@code sos_offers.status}'s {@code CHECK} constraint ({@code V34}).
 *
 * <p>{@link #SELECTED} is an addition to the statuses the product brief listed. Without it
 * the winning offer would be indistinguishable from the other {@link #ACCEPTED} ones, and
 * "which offer did the customer actually pick" would only be answerable by joining back
 * through {@code sos_requests.selected_offer_id}.
 */
public enum SosOfferStatus {

    /** Dispatched, not yet opened by the professional. */
    OFFERED,

    /** The professional opened it. A response-latency signal for the ranker, nothing more. */
    VIEWED,

    /** The professional is available and committed to an ETA. Eligible to become a candidate. */
    ACCEPTED,

    /** The professional declined. */
    REJECTED,

    /** {@code expiresAt} elapsed with no response. */
    EXPIRED,

    /** The customer chose this one. Exactly one per request, at most. */
    SELECTED,

    /** Accepted, but the customer chose someone else — or the request ended without a choice. */
    NOT_SELECTED;

    /** True while the professional can still accept or reject. */
    public boolean isOpen() {
        return this == OFFERED || this == VIEWED;
    }
}
