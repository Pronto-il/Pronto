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
     * @param description the customer's free-text issue description (already
     *                     length-validated by the caller)
     * @param images       resolved image bytes, possibly empty; {@code MockAiClassificationClient}
     *                     ignores this entirely (no vision capability in mock mode)
     * @throws com.pronto.common.exception.ApiException with {@code ErrorCode.AI_SERVICE_ERROR}
     *         if classification cannot be produced (timeout, non-2xx, malformed response,
     *         after retries). The mock client never throws this.
     */
    ClassificationResult classify(String description, List<ImageAttachment> images);
}
