package com.pronto.sos.dto;

import com.pronto.sos.entity.SosOfferStatus;

/**
 * <b>What the customer's SOS screen is allowed to say about one professional.</b> A deliberately
 * two-valued projection of {@link SosOfferStatus}'s seven, and the narrowest thing that can be true.
 *
 * <h2>Why a projection rather than exposing the offer status</h2>
 *
 * {@link SosOfferStatus} is the professional-side lifecycle: {@code OFFERED} and {@code VIEWED} are
 * a real and useful distinction to the ranker (response latency is a scoring signal) and to the
 * professional's own inbox. To the <em>customer</em> they are the same fact — somebody was asked and
 * has not answered — and shipping the difference would invite a screen that renders "opened your
 * request" as progress. It is not progress. Nothing has been promised until an ETA has been
 * committed.
 *
 * <p>Equally, this is not a second state model: nothing is stored in this vocabulary, no transition
 * is driven by it, and every value is derived per response from the {@code sos_offers} row that
 * already exists. {@code sos_offers.status} remains the single source of truth, and
 * {@code SosService#selectProfessional} still authorizes on it directly rather than on this —
 * which is what guarantees a {@link #REQUESTED} professional can never be selected, independently
 * of anything the screen believes.
 *
 * <h2>What is deliberately absent</h2>
 *
 * There is no {@code REJECTED} and no {@code EXPIRED}. An offer in either state is filtered out of
 * the candidate list entirely ({@code SosService#getCandidates}) rather than shown greyed out or in
 * red. Showing them would be actively harmful in two directions: it publishes one professional's
 * commercial decision to decline to a stranger, and it fills an emergency screen with rows the
 * customer cannot act on. {@code NOT_SELECTED} and {@code SELECTED} are likewise absent, because by
 * the time either exists the customer has chosen and the screen has moved on to tracking.
 */
public enum SosCandidateState {

    /**
     * <b>Asked, not answered.</b> The platform dispatched an offer to this professional and their
     * response window is still open ({@code OFFERED} or {@code VIEWED}).
     *
     * <p>The customer sees them so that "we are contacting people" is a visible fact rather than a
     * spinner — but they carry no ETA, no commitment and no way to be chosen. The screen renders
     * them muted for exactly that reason.
     */
    REQUESTED,

    /**
     * <b>Answered, and available.</b> The professional accepted and committed to an arrival time.
     *
     * <p>Still not "the job is theirs" — that is the customer's choice, made later, and recorded as
     * {@link SosOfferStatus#SELECTED}. This is the only state a customer may select from.
     */
    ACCEPTED;

    /**
     * The projection itself. Total over the statuses {@code getCandidates} admits; anything else is
     * a programming error at the filter, not a case to render.
     *
     * @throws IllegalArgumentException for a status that should already have been filtered out —
     *         loud on purpose, because the silent alternative is a declined professional appearing
     *         on a customer's screen as though they were waiting to answer
     */
    public static SosCandidateState fromOfferStatus(SosOfferStatus status) {
        return switch (status) {
            case OFFERED, VIEWED -> REQUESTED;
            case ACCEPTED -> ACCEPTED;
            case REJECTED, EXPIRED, SELECTED, NOT_SELECTED -> throw new IllegalArgumentException(
                    "SosOfferStatus." + status + " is not a customer-visible candidate state; "
                            + "SosService#getCandidates must filter it out before assembly.");
        };
    }
}
