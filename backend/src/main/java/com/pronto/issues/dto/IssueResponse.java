package com.pronto.issues.dto;

import com.pronto.issues.entity.IssueStatus;
import com.pronto.issues.entity.IssueUrgencyType;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/issues}. See
 * {@code docs/architecture/api-contract-issues.md} §2.2.
 */
public record IssueResponse(
        Long id,
        Long customerId,
        Long categoryId,
        String description,
        IssueUrgencyType urgencyType,
        IssueStatus status,
        List<IssueImageResponse> images,
        Instant createdAt
) {
}
