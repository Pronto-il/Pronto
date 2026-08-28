package com.pronto.ai.dto;

/**
 * The three states a classification pass can end in.
 *
 * <p>{@code CLASSIFIED}: the accumulated evidence supports a routing decision — either
 * confidently, or as the controlled low-confidence fallback once the clarification budget is
 * exhausted (see {@code ClassificationSuggestion.lowConfidence}).
 *
 * <p>{@code QUESTIONS}: a specific missing fact could still change which professional Pronto
 * sends, and budget remains — exactly one clarification question is returned. Pronto
 * re-classifies against the full accumulated context after the answer arrives, so this state
 * can legitimately repeat until the configured maximum is reached.
 *
 * <p>{@code UNSUPPORTED_PROFESSION}: classification <b>succeeded</b> — Pronto knows which trade
 * the customer needs — and Pronto does not offer that trade. {@code categoryId}/
 * {@code categoryCode} are {@code null} and {@code detectedProfession} names the trade.
 *
 * <p><b>This is a third state rather than a variant of {@code CLASSIFIED}, on purpose.</b> The
 * two differ in what the customer can do next: a {@code CLASSIFIED} issue proceeds to matching,
 * and this one has nowhere to proceed to. Modelling it as {@code CLASSIFIED} with a null category
 * would push that distinction into every consumer as a null check, and the first consumer to
 * forget it would send the customer into a professional search for a trade with no professionals
 * — which is the "generic empty results" outcome this state exists to replace with an honest one.
 */
public enum ClassificationStatus {
    CLASSIFIED,
    QUESTIONS,
    UNSUPPORTED_PROFESSION
}
