package com.pronto.ai.eval;

/**
 * What actually happened when one {@link EvaluationCase} was run through the pipeline.
 *
 * @param caseId            the case's id
 * @param expectedCategory  ground truth
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
 * @param failureReason     non-null when the run threw; such a case counts as wrong, never as
 *                          silently passing
 */
public record EvaluationOutcome(
        String caseId,
        String expectedCategory,
        String initialCategory,
        String finalCategory,
        Double finalConfidence,
        int questionsAsked,
        boolean lowConfidence,
        boolean unresolved,
        String unmatchedQuestion,
        String failureReason
) {

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
