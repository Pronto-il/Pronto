package com.pronto.ai.client;

import com.pronto.ai.dto.ClassificationRequest;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.ai.dto.ProfessionalBriefRequest;
import com.pronto.ai.dto.ProfessionalBriefResponse;

/**
 * AI abstraction behind which {@code mock}/{@code openai} implementations are swapped via
 * {@code pronto.ai.mode} — mirrors the {@code auth.email.EmailSender} mock/real split from
 * Milestone 1. See {@code docs/architecture/api-contract-issues.md} §3.1.
 *
 * <p>Two responsibilities, two calls, two response models. They are deliberately not merged
 * into one oversized response: routing must be cheap and repeatable (it runs on every
 * clarification round), while the brief is expensive, written for a different audience, and
 * only worth generating once routing is settled.
 *
 * <p>Implementations return raw model output. Validating it against Pronto's real category
 * table and turning it into a routing decision is
 * {@code decision.RoutingDecisionPolicy}'s job, not theirs.
 */
public interface AiClassificationClient {

    /**
     * One routing pass over the complete accumulated evidence. Called again, with the same
     * description/images plus every answer so far, after each clarification answer — never
     * with the newest answer alone.
     *
     * @throws com.pronto.common.exception.ApiException with {@code ErrorCode.AI_SERVICE_ERROR}
     *         if no usable response can be produced (timeout, non-2xx, malformed or
     *         rule-violating output, after retries). The mock client never throws this.
     */
    ClassificationResponse classify(ClassificationRequest request);

    /**
     * The professional preparation brief, generated after the routing category is final.
     *
     * @throws com.pronto.common.exception.ApiException with {@code ErrorCode.AI_SERVICE_ERROR}
     *         if no usable brief can be produced. Callers treat a missing brief as a degraded
     *         but acceptable state — it must never block a booking.
     */
    ProfessionalBriefResponse generateBrief(ProfessionalBriefRequest request);
}
