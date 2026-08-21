package com.pronto.ai.dto;

/**
 * The two states a classification pass can end in.
 *
 * <p>{@code CLASSIFIED}: the accumulated evidence supports a routing decision — either
 * confidently, or as the controlled low-confidence fallback once the clarification budget is
 * exhausted (see {@code ClassificationSuggestion.lowConfidence}).
 *
 * <p>{@code QUESTIONS}: a specific missing fact could still change which professional Pronto
 * sends, and budget remains — exactly one clarification question is returned. Pronto
 * re-classifies against the full accumulated context after the answer arrives, so this state
 * can legitimately repeat until the configured maximum is reached.
 */
public enum ClassificationStatus {
    CLASSIFIED,
    QUESTIONS
}
