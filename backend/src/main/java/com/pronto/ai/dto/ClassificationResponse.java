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
 * @param detectedProfession  the trade the customer actually needs, in Hebrew and in the
 *                            model's own words — <b>free text, deliberately not constrained to
 *                            Pronto's catalogue</b>. This is the field that lets the model be
 *                            right about "טכנאי גז" when Pronto has no gas category, instead of
 *                            being forced to name the nearest thing it is allowed to say. It is
 *                            a <em>label</em>, never a routing target: nothing downstream
 *                            matches on it, and it cannot become a category.
 * @param primaryCategoryCode the Pronto category the model believes that profession maps to,
 *                            or {@code null} when it maps to none. Always validated against a
 *                            real {@code categories.code} — {@code catalog.ServiceCategoryCatalog}
 *                            remains the sole authority on what Pronto supports, and this field
 *                            is only the model's proposal to it.
 * @param confidence          0..1 self-report for {@code primaryCategoryCode}
 * @param needsClarification  the model's own judgment that a missing fact could change which
 *                            professional is sent — one input to the decision, not the decision
 * @param ambiguityReason     short internal explanation of what is unresolved; never shown to
 *                            the customer
 * @param candidates          all plausible <em>Pronto</em> categories with confidences,
 *                            strongest first. Empty when the detected profession maps to none —
 *                            that emptiness, not a self-reported flag, is what the policy reads.
 * @param nextQuestion        the single highest-value question to ask next, or {@code null}
 */
public record ClassificationResponse(
        String detectedProfession,
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
