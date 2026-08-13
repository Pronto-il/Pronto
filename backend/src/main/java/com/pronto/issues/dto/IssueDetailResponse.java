package com.pronto.issues.dto;

import com.pronto.issues.entity.IssueStatus;
import com.pronto.issues.entity.IssueUrgencyType;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/issues/{id}}. See
 * {@code docs/architecture/api-contract-bookings.md} §2.1. Distinct from
 * {@link IssueResponse} (the {@code POST /api/issues} response) — this shape additionally
 * carries {@code categoryCode}, {@code status}, {@code updatedAt}, and {@code latestOrder}.
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
        LatestOrderSummary latestOrder,
        Instant createdAt,
        Instant updatedAt
) {
}
