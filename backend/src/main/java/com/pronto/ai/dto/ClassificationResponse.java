package com.pronto.ai.dto;

import java.util.List;

/**
 * The strongly typed classification model response — one of the two AI response shapes in
 * this package (the other being {@link ProfessionalBriefResponse}). Deliberately narrow: it
 * answers "who should Pronto send, and is that answer safe to act on yet", nothing else. The
 * professional-preparation content lives in its own model and its own call, generated only
 * after routing is final.
 *
 * <p>Produced by OpenAI under a strict JSON Schema and then re-validated in Java
 * ({@code client.ClassificationResponseParser}) — the schema cannot express every invariant
 * (e.g. "if needsClarification is true there must be a usable question"), and an AI response
 * is never trusted blind.
 *
 * @param primaryCategoryCode the model's chosen routing target, or {@code null} when it
 *                            genuinely cannot commit; always validated against a real
 *                            {@code categories.code}
 * @param confidence          0..1 self-report for {@code primaryCategoryCode}
 * @param needsClarification  the model's own judgment that a missing fact could change which
 *                            professional is sent — one input to the decision, not the decision
 * @param ambiguityReason     short internal explanation of what is unresolved; never shown to
 *                            the customer
 * @param candidates          all plausible categories with confidences, strongest first
 * @param nextQuestion        the single highest-value question to ask next, or {@code null}
 */
public record ClassificationResponse(
        String primaryCategoryCode,
        double confidence,
        boolean needsClarification,
        String ambiguityReason,
        List<CategoryCandidate> candidates,
        ClarificationQuestion nextQuestion
) {

    public ClassificationResponse {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
