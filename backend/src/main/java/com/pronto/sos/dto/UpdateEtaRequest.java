package com.pronto.sos.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Wire shape for {@code POST /api/sos/offers/{id}/eta} — a professional revising their arrival
 * estimate after accepting. Required here, unlike on {@link AcceptSosOfferRequest}: revising an
 * ETA to "no value" is not a meaningful operation.
 */
public record UpdateEtaRequest(
        @NotNull @Min(0) @Max(480) Integer estimatedArrivalMinutes
) {
}
