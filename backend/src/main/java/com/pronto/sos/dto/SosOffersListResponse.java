package com.pronto.sos.dto;

import java.util.List;

/** {@code GET /api/sos/offers} — the professional's SOS inbox. */
public record SosOffersListResponse(
        List<SosOfferResponse> offers
) {
}
