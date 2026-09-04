package com.pronto.issues.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Wire shape for {@code POST /api/issues/classify}. See
 * {@code docs/architecture/api-contract-issues.md} §2.1. {@code imageKeys} entries are
 * further validated (existence + ownership, §3.3) in {@code IssuesService} — Bean Validation
 * only covers shape.
 *
 * <p>The endpoint is stateless, so the client round-trips the whole conversation on every
 * call: {@code description}/{@code imageKeys} stay the original values, and
 * {@code clarificationAnswers} accumulates — every question answered so far, not just the
 * newest one. That is what lets the backend re-classify against the complete context each
 * round instead of reacting to the latest answer in isolation.
 *
 * <p>{@code selectedCategoryId} is the customer's own category choice, when the flow has one
 * to offer. It is passed to the model as a <b>hint only</b> and is explicitly allowed to be
 * overruled by the evidence.
 *
 * <p>The {@code @Size(max = 3)} cap is a wire-level sanity bound, not the business rule: the
 * real limit is {@code pronto.ai.routing.max-clarification-questions}, enforced server-side
 * by {@code ai.decision.RoutingDecisionPolicy}. Sending more answers than the configured
 * budget simply leaves no budget remaining, which forces a final decision.
 */
public record ClassifyRequest(
        @NotBlank @Size(min = IssueText.DESCRIPTION_MIN_LENGTH, max = IssueText.DESCRIPTION_MAX_LENGTH)
        String description,
        @Size(max = 6) List<@NotBlank String> imageKeys,
        Long selectedCategoryId,
        @Valid @Size(max = 3) List<ClarificationAnswerRequest> clarificationAnswers
) {
}
