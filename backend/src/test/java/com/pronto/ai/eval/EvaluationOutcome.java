package com.pronto.ai.eval;

import java.util.List;

/**
 * What actually happened when one {@link EvaluationCase} was run through the pipeline.
 *
 * @param caseId            the case's id
 * @param expectedCategory  ground truth
 * @param tier              {@code core} or {@code challenge}, carried through from the dataset
 *                          so the report can present the approved regression number separately
 *                          from the deliberately hard cases
 * @param initialCategory   the routing the very first pass would have produced, before any
 *                          clarification. When the first pass asked a question instead of
 *                          committing, this is its strongest candidate — the model's best
 *                          guess at that moment — which is what makes
 *                          "accuracy before clarification" a meaningful number rather than an
 *                          automatic miss.
 * @param finalCategory     the routing after the full clarification flow; {@code null} if the
 *                          run failed
 * @param finalConfidence   confidence attached to the final routing, {@code null} if unknown
 * @param questionsAsked    how many clarification questions the customer had to answer
 * @param lowConfidence     Pronto committed to this category while recording that it was not
 *                          fully confident. Still a genuine prediction.
 * @param unresolved        Pronto ran out of questions with two materially different
 *                          categories still live (or validated nothing) and deliberately used
 *                          the {@code general_handyman} fallback. <b>Not a prediction</b> —
 *                          {@link EvaluationReport} reports these separately so accuracy
 *                          cannot be improved by quietly diverting hard cases to the fallback.
 * @param unmatchedQuestion a question the scripted answers had no entry for, if any — a gap in
 *                          the dataset rather than in the system
 * @param rounds            every clarification exchange with the classification state either
 *                          side of it, so question quality is inspectable per case and not
 *                          only in aggregate
 * @param latencyMillis     wall-clock for the whole case, across every model call it made
 * @param failureReason     non-null when the run threw; such a case counts as wrong, never as
 *                          silently passing
 */
public record EvaluationOutcome(
        String caseId,
        String expectedCategory,
        String tier,
        String initialCategory,
        String finalCategory,
        Double finalConfidence,
        int questionsAsked,
        boolean lowConfidence,
        boolean unresolved,
        String unmatchedQuestion,
        boolean expectedClarification,
        List<ClarificationRound> rounds,
        long latencyMillis,
        String failureReason
) {

    public EvaluationOutcome {
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
        tier = tier == null || tier.isBlank() ? EvaluationCase.TIER_CORE : tier;
    }

    public boolean isCore() {
        return EvaluationCase.TIER_CORE.equalsIgnoreCase(tier);
    }

    /**
     * Model calls this case consumed: the initial pass plus one re-classification per answer.
     * The unit the OpenAI bill is actually denominated in.
     */
    public int aiCalls() {
        return questionsAsked + 1;
    }

    /**
     * Asked at least one question that moved nothing — no ranking change, no wider margin, no
     * higher confidence. Pure customer friction, and the metric to watch when tuning
     * thresholds downward.
     */
    public boolean askedUselessly() {
        return !rounds.isEmpty() && rounds.stream().noneMatch(ClarificationRound::wasUseful);
    }

    /**
     * The case said the description could not separate the trades, and Pronto committed anyway.
     *
     * <p>Scored independently of whether the guess happened to be right, because a coin flip
     * that lands correctly is still a coin flip — on a paired case like "the door does not
     * close" the identical sentence has two different correct answers, so a system that
     * commits is wrong half the time by construction. Counting only the wrong half would let
     * that behaviour look 50% acceptable instead of 100% unsound.
     */
    public boolean committedWithoutAsking() {
        return expectedClarification && questionsAsked == 0;
    }

    public boolean initiallyCorrect() {
        return expectedCategory.equals(initialCategory);
    }

    public boolean finallyCorrect() {
        return expectedCategory.equals(finalCategory);
    }

    public boolean askedClarification() {
        return questionsAsked > 0;
    }

    /** Did Pronto actually pick a category, as opposed to falling back because it could not? */
    public boolean committedToACategory() {
        return finalCategory != null && !unresolved;
    }

    public boolean failed() {
        return failureReason != null;
    }
}
