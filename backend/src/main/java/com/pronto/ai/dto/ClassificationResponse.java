package com.pronto.ai.dto;

import com.pronto.ai.taxonomy.Intent;
import com.pronto.ai.taxonomy.Urgency;

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
 * @param professionCode      the same answer as {@code detectedProfession}, but drawn from
 *                            {@code taxonomy.ProfessionTaxonomy}'s controlled 50-profession
 *                            list — the machine-readable half of the pair. The free-text field
 *                            stays because it can express a trade the taxonomy has not got yet;
 *                            this one stays because a free-text label cannot be counted,
 *                            compared across prompt versions, or mapped to a dispatch category.
 *                            {@code null} when the model could not place the request in the
 *                            taxonomy at all.
 * @param subcategoryCode     the concrete problem under that profession, e.g. {@code
 *                            BURST_PIPE_OR_MAJOR_LEAK}. Only meaningful together with
 *                            {@code professionCode}, and validated as a pair — subcategory codes
 *                            repeat across professions by design.
 * @param intent              what the customer wants done; {@code null} when the model did not
 *                            supply a usable value
 * @param urgency             how soon, judged from the described situation rather than from the
 *                            trade; {@code null} when not usably supplied
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
        String professionCode,
        String subcategoryCode,
        Intent intent,
        Urgency urgency,
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

    /**
     * The pre-taxonomy shape: everything the classifier returned before professions,
     * subcategories, intent and urgency existed, with those four left {@code null}.
     *
     * <p>Kept so that the many tests and fixtures written against the routing decision — which
     * is genuinely unaffected by the new fields — do not have to restate four nulls each to go
     * on asserting the same thing. Production never uses it:
     * {@code client.ClassificationResponseParser} always fills the full shape, and a caller that
     * silently produced a classification with no profession would be hiding exactly the gap
     * this constructor makes obvious.
     */
    public ClassificationResponse(String detectedProfession, String primaryCategoryCode, double confidence,
                                   boolean needsClarification, String ambiguityReason,
                                   List<CategoryCandidate> candidates, ClarificationQuestion nextQuestion) {
        this(detectedProfession, null, null, null, null, primaryCategoryCode, confidence,
                needsClarification, ambiguityReason, candidates, nextQuestion);
    }
}
