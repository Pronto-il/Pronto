package com.pronto.ai.client;

import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationStatus;

import java.util.List;

/**
 * Raw result from an {@link AiClassificationClient}. When {@code status == CLASSIFIED},
 * {@code categoryCode} is a {@code categories.code}, not yet resolved to a row (that's
 * {@code service.ClassificationService}'s job, including the fallback-to-
 * {@code general_handyman} logic if {@code categoryCode} doesn't match any seeded category)
 * and {@code questions} is empty. When {@code status == QUESTIONS}, {@code categoryCode} is
 * {@code null} and {@code questions} holds 1-3 clarification questions. {@code confidence}
 * is nullable — see {@code docs/architecture/api-contract-issues.md} §2.1's response field
 * notes.
 */
public record ClassificationResult(ClassificationStatus status, String categoryCode, Double confidence,
                                    String explanation, List<ClarificationQuestion> questions) {
}
