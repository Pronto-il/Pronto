package com.pronto.ai.decision;

import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.taxonomy.Intent;
import com.pronto.ai.taxonomy.Urgency;

import java.util.List;

/**
 * What the application decided to do with one validated AI classification response — the
 * output of {@link RoutingDecisionPolicy}, and the only place the routing branch is made.
 *
 * @param outcome        see {@link Outcome}
 * @param detectedProfession the trade the customer actually needs, in Hebrew, as the model named
 *                       it — carried through on every outcome, not only the unsupported one, so
 *                       telemetry can compare "what the model said this was" against where it
 *                       was routed. May be {@code null} when the model supplied no label.
 * @param professionCode the same trade as a controlled {@code taxonomy.ProfessionTaxonomy} code.
 *                       <b>Carried on every outcome, including the ones with no category.</b>
 *                       This is the classification result; {@code category} below is the dispatch
 *                       result, and the whole point of keeping both is that one can be right
 *                       while the other is absent.
 * @param subcategoryCode the concrete problem under that profession, already validated as
 *                       belonging to it
 * @param intent         what the customer wants done, or {@code null}
 * @param urgency        how soon, judged from the situation, or {@code null}
 * @param category       the routing target; {@code null} for {@link Outcome#ASK_CLARIFICATION}
 *                       and for {@link Outcome#UNSUPPORTED_PROFESSION}, where there is
 *                       deliberately no Pronto category to name
 * @param confidence     the routed category's self-reported confidence. {@code null} for
 *                       {@link Outcome#FINAL_UNRESOLVED}, where the routed category is the
 *                       fallback rather than a candidate the model actually argued for —
 *                       reporting the top candidate's confidence there would attach a number
 *                       to a category nobody predicted.
 * @param candidates     validated candidates, strongest first, unknown codes already dropped.
 *                       Retained even when unresolved: it is exactly the "0.48 vs 0.45" shape
 *                       that explains why the fallback was used.
 * @param ambiguityReason internal-only note about what remains unresolved
 * @param question       the question to ask; non-{@code null} exactly for
 *                       {@link Outcome#ASK_CLARIFICATION}
 */
public record RoutingDecision(
        Outcome outcome,
        String detectedProfession,
        String professionCode,
        String subcategoryCode,
        Intent intent,
        Urgency urgency,
        ServiceCategory category,
        Double confidence,
        List<CategoryCandidate> candidates,
        String ambiguityReason,
        ClarificationQuestion question
) {

    /**
     * Running out of clarification budget and deciding where to route are two separate
     * questions. {@link #FINAL_LOW_CONFIDENCE} and {@link #FINAL_UNRESOLVED} are the two
     * different answers to the second one once the first is settled.
     */
    public enum Outcome {
        /** Evidence is sufficient. Route here and stop asking. */
        FINAL,
        /**
         * Some uncertainty remains, but one validated candidate is clearly dominant and the
         * residual doubt is not material to routing — a plumber is still the right person to
         * send. Route there and record that Pronto was not fully confident.
         */
        FINAL_LOW_CONFIDENCE,
        /**
         * Pronto still considers two materially different professional categories live, and
         * has no questions left to separate them. Routing to whichever ranked first would
         * disguise an open question as a decision, so the category is the seeded
         * {@code general_handyman} fallback and the state is recorded as unresolved.
         *
         * <p>Also covers the case where nothing the model returned survived validation.
         */
        FINAL_UNRESOLVED,
        /** A specific missing fact could still change the routing, and budget remains. */
        ASK_CLARIFICATION,
        /**
         * The trade the customer needs was identified, and Pronto does not offer it.
         *
         * <p><b>Not a failure, and not an unresolved case.</b> {@link #FINAL_UNRESOLVED} means
         * "two Pronto categories are still live and I cannot separate them"; this means "I know
         * exactly which professional is needed and Pronto has none of them". Conflating the two
         * is how a gas technician becomes a handyman: the fallback exists to pick between
         * plausible Pronto trades, and there is nothing plausible to pick between here.
         *
         * <p><b>Deliberately independent of confidence.</b> The model may be 98% sure the answer
         * is a gas technician; that is a confident, correct classification whose only property is
         * that Pronto cannot serve it. It is neither low-confidence nor a reason to ask a
         * question — see {@code RoutingDecisionPolicy}'s ordering.
         *
         * <p>{@code category} is {@code null}: naming any Pronto category here, even the
         * fallback, would be the forcing this outcome exists to end.
         */
        UNSUPPORTED_PROFESSION
    }

    public RoutingDecision {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /**
     * The pre-taxonomy shape, with the four classification-layer fields left {@code null}.
     * Retained so the existing routing-policy tests — which assert the dispatch branch, and
     * are genuinely independent of the classification labels — keep reading as assertions
     * about routing rather than about four nulls.
     */
    public RoutingDecision(Outcome outcome, String detectedProfession, ServiceCategory category,
                            Double confidence, List<CategoryCandidate> candidates, String ambiguityReason,
                            ClarificationQuestion question) {
        this(outcome, detectedProfession, null, null, null, null, category, confidence, candidates,
                ambiguityReason, question);
    }

    public boolean isFinal() {
        return outcome != Outcome.ASK_CLARIFICATION;
    }

    /** True when Pronto identified the trade and does not offer it. */
    public boolean isUnsupportedProfession() {
        return outcome == Outcome.UNSUPPORTED_PROFESSION;
    }
}
