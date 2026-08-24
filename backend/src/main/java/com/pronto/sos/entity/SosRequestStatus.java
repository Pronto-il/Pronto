package com.pronto.sos.entity;

/**
 * Mirrors {@code sos_requests.status}'s {@code CHECK} constraint ({@code V34}).
 *
 * <p>Legal transitions live in {@code sos.service.SosStateMachine}, not here — this enum is
 * only the vocabulary. Nothing outside that class may change a status.
 *
 * <p><b>On the missing {@code CLASSIFIED}/{@code READY_FOR_MATCHING} step.</b> The product
 * brief sketched one, but in this codebase classification has already happened by the time an
 * SOS request can exist: an SOS request is anchored to an {@code issues} row, and
 * {@code issues.category_id} is settled by the AI routing pipeline ({@code ai.decision}) as
 * part of issue creation. A status that every request would pass through in the same
 * microsecond, carrying no decision, would be ceremony. {@link #CREATED} still exists as a
 * distinct initial state because matching can genuinely fail before it starts (no eligible
 * professionals), and the event log should show that.
 */
public enum SosRequestStatus {

    /** Row inserted, matching not yet started. */
    CREATED,

    /** Candidate pool being assembled and ranked. */
    MATCHING,

    /** Offers dispatched; waiting for professionals to accept. Bounded by {@code matchingExpiresAt}. */
    WAITING_FOR_PROFESSIONALS,

    /**
     * <b>At least one professional is available and the customer may choose, right now.</b>
     * <b>Unbounded by any clock</b> (MS3 follow-up): a professional who has committed to come is
     * a real option, and no timer deletes it. This status ends when the customer selects or
     * cancels — or, in the degenerate case where every offer has lapsed with nothing accepted,
     * when there is genuinely nothing left to choose from.
     *
     * <p>Reached on the <em>first</em> acceptance, not on a quota. A customer with a burst pipe
     * and one real option in hand has nothing to gain from being made to wait for a second and a
     * third, so the window opens the moment there is anything to choose between. The search does
     * not stop when it opens: professionals with live offers keep answering and keep appearing
     * (see {@link #isAcceptingProfessionalResponses()}), and the customer can widen it further
     * with "סרוק שוב". What ends the search is the customer choosing.
     */
    WAITING_FOR_CUSTOMER_SELECTION,

    /** The customer chose. An {@code orders} row now exists, {@code PENDING} the professional's confirmation. */
    PROFESSIONAL_SELECTED,

    /** The selected professional confirmed. The order is {@code CONFIRMED}. */
    CONFIRMED,

    ON_THE_WAY,

    /**
     * The professional is on site. SOS-only — {@code orders} has no equivalent status and
     * stays {@code ON_THE_WAY} until completion. An urgent flow needs the "they're here" beat
     * that a scheduled booking does not.
     */
    ARRIVED,

    COMPLETED,

    // ---- terminal failure states ----

    /** Cancelled by the customer, the selected professional, or the system. */
    CANCELLED,

    /** A deadline elapsed: nobody accepted in time, or the customer did not choose in time. */
    EXPIRED,

    /** Matching found nobody eligible at all — distinct from {@link #EXPIRED}, which means nobody answered. */
    FAILED;

    /**
     * True while a professional holding a live offer may still answer it, and while the customer
     * may still widen the search.
     *
     * <p><b>Two statuses, and that is the point.</b> Selection opening no longer stops the search:
     * the customer can choose from the first professional who answered <em>and</em> keep receiving
     * more, which would be contradictory if "the window is open" also meant "nobody else may
     * respond". What stops the search is {@link #hasSelection()} — a choice, or a terminal state.
     */
    public boolean isAcceptingProfessionalResponses() {
        return this == WAITING_FOR_PROFESSIONALS || this == WAITING_FOR_CUSTOMER_SELECTION;
    }

    /** Terminal states accept no further transitions. */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == EXPIRED || this == FAILED;
    }

    /**
     * True once a specific professional owns the job — the point after which operational
     * updates ({@code ON_THE_WAY}/{@code ARRIVED}/{@code COMPLETED}) become possible and only
     * {@code sos_requests.selected_professional_id} may make them.
     */
    public boolean hasSelection() {
        return this == PROFESSIONAL_SELECTED || this == CONFIRMED || this == ON_THE_WAY
                || this == ARRIVED || this == COMPLETED;
    }
}
