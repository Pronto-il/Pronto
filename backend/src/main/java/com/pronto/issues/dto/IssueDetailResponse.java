package com.pronto.issues.dto;

import com.pronto.issues.entity.IssueStatus;
import com.pronto.issues.entity.IssueUrgencyType;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/issues/{id}}. See
 * {@code docs/architecture/api-contract-bookings.md} §2.1.
 *
 * <p><b>Customer report and Pronto analysis are separate fields, on purpose.</b>
 * {@code description} and {@code images} are exactly what the customer submitted and are
 * never rewritten by anything in this system; {@code clarifications} are the customer's own
 * answers, also verbatim. {@code prontoAnalysis} is the AI's interpretation and is the only
 * AI-authored content in this response. Clients render the two under distinct headings.
 *
 * <p>{@code prontoAnalysis} is populated only for a {@code PROFESSIONAL} caller with an order
 * on this issue — it is preparation material for whoever is going, not customer-facing
 * content. It is {@code null} for the customer viewing their own issue, and {@code null} for
 * any issue created before the brief feature existed.
 */
public record IssueDetailResponse(
        Long id,
        Long customerId,
        Long categoryId,
        String categoryCode,
        String description,
        IssueUrgencyType urgencyType,
        IssueStatus status,
        List<IssueImageResponse> images,
        List<ClarificationEntryResponse> clarifications,
        ProntoAnalysisResponse prontoAnalysis,
        LatestOrderSummary latestOrder,
        Instant createdAt,
        Instant updatedAt
) {
}
