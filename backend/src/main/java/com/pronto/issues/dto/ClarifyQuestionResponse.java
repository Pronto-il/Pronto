package com.pronto.issues.dto;

import java.util.List;

/**
 * One clarification question as the customer's app receives it.
 *
 * <p>Deliberately narrower than {@code ai.dto.ClarificationQuestion}: the internal
 * {@code distinguishesBetween} field — which competing categories the question is meant to
 * separate — stays backend-only. It is genuinely useful for validation, logging and
 * debugging, and completely uninteresting to a customer who just wants to be asked one clear
 * question. See {@code docs/architecture/api-contract-issues.md} §2.1.
 */
public record ClarifyQuestionResponse(String id, String question, List<String> options) {
}
