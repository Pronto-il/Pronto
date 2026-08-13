package com.pronto.issues.dto;

import java.time.Instant;

/**
 * One entry in {@code POST /api/issues}'s response {@code images} array. See
 * {@code docs/architecture/api-contract-issues.md} §2.2.
 */
public record IssueImageResponse(Long id, String imageUrl, Instant uploadedAt) {
}
