package com.pronto.ai.eval.taxonomy;

import com.pronto.ai.dto.ClassificationStatus;

/**
 * What the classifier produced for one labelled case, and how it scored.
 *
 * <p><b>Classification and dispatch are scored separately, and that separation is the point of
 * this record.</b> A correct {@code GAS_TECHNICIAN} on a trade Pronto does not sell is a
 * <em>successful classification</em>: {@link #professionCorrect} is true, and
 * {@link #dispatchable} is false. Collapsing the two into one "was it right" flag would mean the
 * only way to raise the score is to shrink the taxonomy to what Pronto already sells, which is
 * the opposite of what the taxonomy is for.
 *
 * @param promptVersion       the prompt that produced this, e.g. {@code classification-v6}
 * @param model               the model that produced it
 * @param taxonomyVersion     the label space it was scored against
 * @param status              the pipeline's terminal state
 * @param predictedProfession the profession the classifier chose, or {@code null}
 * @param confidence          the model's self-reported confidence for the routed category
 * @param needsClarification  whether the pipeline asked rather than committing
 * @param dispatchCategory    the category actually routed to, or {@code null} for an
 *                            unsupported-profession or unresolved outcome
 * @param dispatchable        whether Pronto could dispatch this at all
 * @param clarificationRounds how many questions the customer had to answer
 * @param error               non-{@code null} when the pipeline itself failed. Such a case is
 *                            excluded from every accuracy denominator and reported on its own —
 *                            an unavailable provider is not a wrong answer, and averaging the two
 *                            together produces a number that means neither.
 * @param failureType         the manual error-analysis bucket. Always {@code null} as produced;
 *                            filled in by a human during review. See {@link FailureType}.
 * @param latencyMillis       wall-clock for the whole call including any retries
 * @param attempts            HTTP attempts; {@code > 1} means the retry policy fired
 * @param promptTokens        billed input tokens
 * @param completionTokens    billed output tokens, <b>including</b> reasoning
 * @param reasoningTokens     the reasoning subset of {@code completionTokens} — tokens the
 *                            customer pays for and never sees. Zero on a non-reasoning model.
 */
public record TaxonomyEvaluationOutcome(
        int id,
        String split,
        String description,
        String promptVersion,
        String model,
        String taxonomyVersion,
        ClassificationStatus status,
        String expectedProfession,
        String predictedProfession,
        String expectedSubcategory,
        String predictedSubcategory,
        String expectedIntent,
        String predictedIntent,
        String expectedUrgency,
        String predictedUrgency,
        boolean expectedNeedsClarification,
        boolean needsClarification,
        Double confidence,
        String expectedDispatchCategory,
        String dispatchCategory,
        boolean dispatchable,
        int clarificationRounds,
        String descriptionStyle,
        String evalType,
        String error,
        FailureType failureType,
        long latencyMillis,
        int attempts,
        int promptTokens,
        int completionTokens,
        int reasoningTokens
) {

    /** True when the pipeline errored rather than producing a judgeable answer. */
    public boolean isError() {
        return error != null;
    }

    public boolean professionCorrect() {
        return !isError() && equalsIgnoringCase(expectedProfession, predictedProfession);
    }

    /**
     * Subcategory correctness is <b>conditional on the profession being right</b>.
     *
     * <p>A subcategory only means anything under its profession — {@code NOT_COOLING} exists
     * under two of them — so crediting it while the profession is wrong would score a coincidence.
     */
    public boolean subcategoryCorrect() {
        return professionCorrect() && equalsIgnoringCase(expectedSubcategory, predictedSubcategory);
    }

    public boolean intentCorrect() {
        return !isError() && equalsIgnoringCase(expectedIntent, predictedIntent);
    }

    public boolean urgencyCorrect() {
        return !isError() && equalsIgnoringCase(expectedUrgency, predictedUrgency);
    }

    public boolean clarificationCorrect() {
        return !isError() && expectedNeedsClarification == needsClarification;
    }

    /**
     * Did the dispatch layer do the right thing <em>given</em> the classification?
     *
     * <p>Deliberately measured only where the profession was right: dispatch cannot be judged on
     * a case whose classification already failed, because routing the wrong trade correctly is
     * still the wrong visit. Where the profession is right, this asks the narrow question the
     * dispatch layer is actually responsible for — did a dispatchable trade reach its category,
     * and did an undispatchable one correctly reach nothing?
     */
    public boolean dispatchCorrect() {
        if (!professionCorrect()) {
            return false;
        }
        if (expectedDispatchCategory == null) {
            return dispatchCategory == null;
        }
        return equalsIgnoringCase(expectedDispatchCategory, dispatchCategory);
    }

    /**
     * The dangerous failure: confidently wrong, with no question asked.
     *
     * <p>Worse than a low-confidence miss, because nothing in the product flow flags it — the
     * customer is simply sent the wrong trade and finds out when someone arrives.
     */
    public boolean confidentlyWrong(double highConfidenceThreshold) {
        return !isError() && !professionCorrect() && !needsClarification
                && confidence != null && confidence >= highConfidenceThreshold;
    }

    /**
     * The failure this whole architecture exists to prevent: a trade Pronto does not dispatch
     * that nonetheless produced a booking into some other category.
     */
    public boolean forcedIntoDispatch() {
        return !isError() && expectedDispatchCategory == null && dispatchCategory != null;
    }

    private static boolean equalsIgnoringCase(String expected, String actual) {
        return expected != null && actual != null && expected.equalsIgnoreCase(actual);
    }
}
