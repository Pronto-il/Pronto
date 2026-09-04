package com.pronto.issues.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One customer answer to a clarification question previously returned by
 * {@code POST /api/issues/classify} (as a {@code ClassifyResponse.questions} entry) when it
 * returned {@code status = QUESTIONS}. Carries the question text itself, not just its id —
 * {@code /classify} is stateless (§2.1), so there is no server-side session to look the
 * original question back up by id; the caller round-trips it. See
 * {@code docs/architecture/api-contract-issues.md} §2.1's clarification-question extension.
 */
public record ClarificationAnswerRequest(
        /**
         * The question text as this service produced it, echoed back. Not customer-editable, so the
         * cap is a wire-level sanity bound rather than a UX limit — one generated question is a
         * sentence, and nothing legitimate comes close to this.
         */
        @NotBlank @Size(max = QUESTION_MAX_LENGTH) String question,
        /**
         * What the customer answered. Today the UI only ever sends one of the generated options,
         * but the field is client-supplied and was previously unbounded, so it carries the
         * free-text answer limit.
         */
        @NotBlank @Size(max = ANSWER_MAX_LENGTH) String answer
) {
    public static final int QUESTION_MAX_LENGTH = 500;
    public static final int ANSWER_MAX_LENGTH = 200;
}
