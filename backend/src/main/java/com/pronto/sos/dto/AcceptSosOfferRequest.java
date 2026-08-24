package com.pronto.sos.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Wire shape for {@code POST /api/sos/offers/{id}/accept}.
 *
 * <p>{@code estimatedArrivalMinutes} is <b>required</b> as of the MS3 SOS lifecycle redesign.
 * It used to be optional, with the platform's own dispatch-time estimate standing in when it was
 * omitted — which meant a candidate could reach the customer's decision screen advertising a
 * figure no professional had actually agreed to. Accepting an urgent call is a commitment to
 * arrive within a stated time, and the customer chooses on that number, so there is no
 * meaningful "yes, but I won't say when".
 *
 * <p>The value is also <b>final</b> once accepted: it is written to
 * {@code sos_offers.promised_eta_minutes} and cannot be revised afterwards (see
 * {@code SosOfferService#updateEta}). A professional who cannot make it cancels; they do not
 * quietly move the number the customer picked them for.
 *
 * <p>Capped at 480 minutes. Not a business rule so much as a guard against a fat-fingered entry
 * (a professional typing 900 for 90) reaching the customer's decision screen as an eight-hour
 * ETA on an urgent call.
 */
public record AcceptSosOfferRequest(
        @NotNull @Min(0) @Max(480) Integer estimatedArrivalMinutes
) {
}
