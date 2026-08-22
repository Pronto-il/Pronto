package com.pronto.sos.service;

/**
 * How much of an SOS request's service address the caller looking at it may see.
 *
 * <p><b>The rule this type exists to make unforgettable:</b> availability is not assignment.
 * Offers go out to up to {@code pronto.sos.emergency-candidate-pool-size} professionals, and a
 * professional saying "I am available and can come" changes nothing about who is coming — the
 * customer has not chosen yet, and may well choose somebody else. Until they do, none of those
 * professionals has any business holding a stranger's street address.
 *
 * <p>Before this existed, {@code SosRequestResponse} was assembled one way for everybody, so
 * {@code GET /api/sos/requests/{id}} handed the full address to any professional merely holding
 * an offer — while the realtime offer payload and {@code SosOfferResponse} both correctly
 * withheld it. The privacy model was real in two places out of three, which is the same as not
 * being real. Making the assembler demand this value at every call site is what stops a fourth
 * place from quietly getting it wrong: there is no default, so a new caller cannot forget to
 * decide.
 *
 * @see SosResponseAssembler#toRequestResponse(com.pronto.sos.entity.SosRequest, SosAddressAccess)
 */
public enum SosAddressAccess {

    /**
     * The exact address, as stored. The customer who owns the request always; the professional
     * the customer actually selected, from the moment of selection onward.
     */
    FULL,

    /**
     * Street name and city — and nothing else that would locate a specific door. House number,
     * apartment, floor, entrance, address notes, latitude and longitude are all nulled out. This
     * is what a professional who has been offered the job sees, including one who has responded
     * {@code ACCEPTED}.
     *
     * <p><b>Why the street is included, when it used to be city-only.</b> A professional deciding
     * whether to commit to an arrival time needs to know roughly where they are going: "Tel Aviv"
     * spans an hour of driving at rush hour, and an ETA guessed against a city centroid is a
     * promise made to the customer on a number nobody could have estimated. A street name closes
     * that gap. A house number does not help estimate anything — it only tells a stranger which
     * door to knock on, which is precisely the thing selection is supposed to grant.
     *
     * <p>So the line is drawn at "enough to estimate the journey", not "enough to make the
     * journey": street plus city plus the offer's own {@code distanceKm} is deliberately enough
     * to decide whether to respond and how fast, and deliberately not enough to turn up
     * uninvited.
     */
    STREET_AND_CITY
}
