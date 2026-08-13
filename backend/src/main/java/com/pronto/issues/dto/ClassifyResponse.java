package com.pronto.issues.dto;

/**
 * Response body for {@code POST /api/issues/classify}. See
 * {@code docs/architecture/api-contract-issues.md} §2.1.
 */
public record ClassifyResponse(Long suggestedCategoryId, String suggestedCategoryCode, Double confidence,
                                String explanation) {
}
