package com.pronto.issues.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * One customer answer to a clarification question previously returned by
 * {@code POST /api/issues/classify} (as a {@code ClassifyResponse.questions} entry) when it
 * returned {@code status = QUESTIONS}. Carries the question text itself, not just its id —
 * {@code /classify} is stateless (§2.1), so there is no server-side session to look the
 * original question back up by id; the caller round-trips it. See
 * {@code docs/architecture/api-contract-issues.md} §2.1's clarification-question extension.
 */
public record ClarificationAnswerRequest(
        @NotBlank String question,
        @NotBlank String answer
) {
}
