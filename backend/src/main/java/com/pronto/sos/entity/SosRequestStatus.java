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
     * Enough professionals accepted (or the response window closed with at least one). The
     * customer now has until {@code selectionExpiresAt} — roughly two minutes — to choose.
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
