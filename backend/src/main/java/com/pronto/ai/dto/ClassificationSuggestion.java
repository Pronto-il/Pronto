package com.pronto.ai.dto;

/**
 * Output of {@code service.ClassificationService.classify} — a {@code categories} row
 * already resolved (joined on {@code code}, fallback applied if needed), ready for
 * {@code issues} to map directly into {@code POST /api/issues/classify}'s response. See
 * {@code docs/architecture/api-contract-issues.md} §2.1.
 */
public record ClassificationSuggestion(Long categoryId, String categoryCode, Double confidence, String explanation) {
}
