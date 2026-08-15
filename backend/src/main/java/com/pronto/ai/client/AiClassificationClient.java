package com.pronto.ai.client;

import java.util.List;

/**
 * AI issue-classification abstraction behind which {@code mock}/{@code openai}
 * implementations are swapped via {@code pronto.ai.mode} — mirrors the
 * {@code auth.email.EmailSender} mock/real split from Milestone 1. See
 * {@code docs/architecture/api-contract-issues.md} §3.1.
 */
public interface AiClassificationClient {

    /**
     * Initial classification pass. May return either {@code CLASSIFIED} (the evidence is
     * clear/consistent) or {@code QUESTIONS} (the description and images meaningfully
     * disagree, or more than one category is realistically possible — up to 3 clarification
     * questions are returned instead of guessing). See
     * {@code docs/architecture/api-contract-issues.md} §2.1's clarification-question
     * extension.
     *
     * @param description the customer's free-text issue description (already
     *                     length-validated by the caller)
     * @param images       resolved image bytes, possibly empty; {@code MockAiClassificationClient}
     *                     ignores this entirely (no vision capability in mock mode)
     * @throws com.pronto.common.exception.ApiException with {@code ErrorCode.AI_SERVICE_ERROR}
     *         if classification cannot be produced (timeout, non-2xx, malformed response,
     *         after retries). The mock client never throws this.
     */
    ClassificationResult classify(String description, List<ImageAttachment> images);

    /**
     * Exactly one additional classification pass after the customer has answered the
     * clarification questions from a prior {@code QUESTIONS} result. Always resolves to
     * {@code CLASSIFIED} — there is no second clarification round.
     *
     * @param description           same original description passed to {@link #classify}
     * @param images                same original images passed to {@link #classify}
     * @param clarificationAnswers  the clarification questions and the customer's answers,
     *                              non-empty, at most 3
     * @throws com.pronto.common.exception.ApiException with {@code ErrorCode.AI_SERVICE_ERROR}
     *         if a final classification cannot be produced, including if the underlying AI
     *         response violates the "no second question round" rule.
     */
    ClassificationResult classifyWithClarification(String description, List<ImageAttachment> images,
                                                     List<ClarificationAnswer> clarificationAnswers);
}
