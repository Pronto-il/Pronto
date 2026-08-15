package com.pronto.ai.client;

/**
 * One customer answer to a clarification question previously returned by
 * {@link AiClassificationClient#classify}. Carries the question text itself (not just an id)
 * since the final classification prompt has no server-side session to look the original
 * question text back up by id — the caller round-trips it. See
 * {@link AiClassificationClient#classifyWithClarification}.
 */
public record ClarificationAnswer(String question, String answer) {
}
