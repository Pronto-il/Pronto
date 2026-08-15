package com.pronto.issues.dto;

import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationStatus;

import java.util.List;

/**
 * Response body for {@code POST /api/issues/classify}. See
 * {@code docs/architecture/api-contract-issues.md} §2.1.
 *
 * <p>When {@code status = CLASSIFIED}, {@code suggestedCategoryId}/{@code suggestedCategoryCode}
 * are populated and {@code questions} is empty. When {@code status = QUESTIONS} (the written
 * description and the attached images meaningfully disagree, or more than one category is
 * realistically possible), {@code suggestedCategoryId}/{@code suggestedCategoryCode} are
 * {@code null} and {@code questions} holds 1-3 clarification questions for the frontend to
 * display; the customer's answers are then sent back on a second call to this same endpoint
 * (see {@code ClassifyRequest.clarificationAnswers}).
 */
public record ClassifyResponse(ClassificationStatus status, Long suggestedCategoryId, String suggestedCategoryCode,
                                Double confidence, String explanation, List<ClarificationQuestion> questions) {
}
