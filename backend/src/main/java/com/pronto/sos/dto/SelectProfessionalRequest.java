package com.pronto.sos.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Wire shape for {@code POST /api/sos/requests/{id}/select}.
 *
 * <p>Identifies the choice by {@code offerId} rather than {@code professionalId}: the offer is
 * what carries the ETA and the price the professional actually committed to, and selecting by
 * professional would leave "which of their offers did you mean" ambiguous if a professional
 * ever held two. It also makes the guard natural — the offer must be {@code ACCEPTED} on
 * <em>this</em> request, which is a single check rather than a lookup plus a check.
 */
public record SelectProfessionalRequest(
        @NotNull @Positive Long offerId
) {
}
