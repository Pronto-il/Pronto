package com.pronto.bookings.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/bookings/professionals?issueId=}. See
 * {@code docs/architecture/api-contract-bookings.md} §2.2.
 */
public record ProfessionalListingResponse(Long issueId, Long categoryId, List<ProfessionalCard> professionals) {
}
