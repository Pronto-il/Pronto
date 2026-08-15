package com.pronto.ai.dto;

import java.util.List;

/**
 * Output of {@code service.ClassificationService.classify}/{@code classifyWithClarification}.
 * When {@code status == CLASSIFIED}, {@code categoryId}/{@code categoryCode} are a
 * {@code categories} row already resolved (joined on {@code code}, fallback applied if
 * needed) and {@code questions} is empty. When {@code status == QUESTIONS},
 * {@code categoryId}/{@code categoryCode} are {@code null} and {@code questions} holds 1-3
 * clarification questions. Ready for {@code issues} to map directly into
 * {@code POST /api/issues/classify}'s response. See
 * {@code docs/architecture/api-contract-issues.md} §2.1.
 */
public record ClassificationSuggestion(ClassificationStatus status, Long categoryId, String categoryCode,
                                        Double confidence, String explanation,
                                        List<ClarificationQuestion> questions) {
}
