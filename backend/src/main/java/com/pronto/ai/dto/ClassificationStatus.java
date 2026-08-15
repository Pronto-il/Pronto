package com.pronto.ai.dto;

/**
 * The two states an AI classification pass can end in — see
 * {@code docs/architecture/api-contract-issues.md} §2.1's clarification-question extension.
 * {@code CLASSIFIED}: the evidence (description + images) was clear/consistent enough to
 * pick a category outright. {@code QUESTIONS}: the description and images meaningfully
 * disagreed or were ambiguous, so up to 3 clarification questions are returned instead of
 * a guess.
 */
public enum ClassificationStatus {
    CLASSIFIED,
    QUESTIONS
}
