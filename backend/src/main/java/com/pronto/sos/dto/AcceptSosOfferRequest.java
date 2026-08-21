package com.pronto.sos.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Wire shape for {@code POST /api/sos/offers/{id}/accept}.
 *
 * <p>{@code estimatedArrivalMinutes} is optional: when omitted the platform's own estimate,
 * already stored on the offer at dispatch, stands. When supplied it replaces that estimate —
 * the professional knows their current job and their traffic better than
 * {@code matching.ApproximateDistanceEtaStrategy} does, and the customer is choosing partly on
 * this number.
 *
 * <p>Capped at 480 minutes. Not a business rule so much as a guard against a fat-fingered entry
 * (a professional typing 900 for 90) reaching the customer's decision screen as an eight-hour
 * ETA on an urgent call.
 */
public record AcceptSosOfferRequest(
        @Min(0) @Max(480) Integer estimatedArrivalMinutes
) {
}
