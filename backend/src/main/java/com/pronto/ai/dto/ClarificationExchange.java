package com.pronto.ai.dto;

/**
 * A clarification question that was already asked, together with the customer's answer.
 *
 * <p>The full accumulated list is passed into every subsequent classification call and into
 * the Professional Brief call — never just the most recent answer (see
 * {@code service.ClassificationService}). Carries the question text itself rather than an id
 * because {@code POST /api/issues/classify} is stateless: there is no server-side session to
 * look the original question back up by id, so the caller round-trips it.
 */
public record ClarificationExchange(String question, String answer) {
}
