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
     * City only — every exact-location field is nulled out: street, house number, apartment,
     * floor, entrance, address notes, latitude and longitude. This is what a professional who
     * has been offered the job sees, including one who has responded {@code ACCEPTED}.
     *
     * <p>City plus the offer's own {@code distanceKm}/{@code estimatedArrivalMinutes} is
     * deliberately enough to decide whether to respond, and deliberately not enough to turn up
     * uninvited.
     */
    CITY_ONLY
}
