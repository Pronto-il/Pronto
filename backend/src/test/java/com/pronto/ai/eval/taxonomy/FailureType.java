package com.pronto.ai.eval.taxonomy;

/**
 * The error-analysis buckets a failed case is sorted into during review.
 *
 * <p><b>Nothing infers these automatically, by design.</b> Telling a prompt problem from a
 * taxonomy problem from a mislabelled row requires reading the description and thinking about
 * it; a heuristic that guessed would produce confident category counts that nobody had checked,
 * and those counts would then drive which fix gets built. The enum exists so that human
 * judgment has somewhere to go and so the buckets stay the same words from one review to the
 * next — {@code TaxonomyEvaluationOutcome.failureType} is the slot it goes in.
 *
 * <p>Roughly in the order worth checking, because the cheap explanations are also the common
 * ones: a wrong label and an unanswerable description account for more failures than a genuine
 * model limitation, and assuming otherwise leads to rewriting a prompt that was never at fault.
 */
public enum FailureType {

    /**
     * The taxonomy and the label are right, and the prompt did not lead the model to them — a
     * missing boundary rule, a misleading example, an instruction that reads two ways. The
     * fixable-today bucket.
     */
    PROMPT_ERROR,

    /**
     * The label space itself is wrong here: two professions that genuinely overlap, a
     * subcategory that no real description maps to, a symptom with no home. No prompt wording
     * fixes this — the taxonomy has to change.
     */
    TAXONOMY_ERROR,

    /**
     * The description honestly does not determine the answer, and the expected label is one
     * defensible reading of several. Asking would have been correct; scoring it as a
     * classification failure blames the model for the customer's brevity.
     */
    AMBIGUOUS_INPUT,

    /**
     * The dataset is wrong and the model is right. Expected in a synthetically generated set,
     * and the reason a failure list is read before it is acted on rather than after.
     */
    INCORRECT_GROUND_TRUTH,

    /**
     * The prompt, the taxonomy and the label are all sound, and the model still got it wrong —
     * usually on long, contradictory or very short input. The bucket that argues for a stronger
     * model rather than more prompt text.
     */
    MODEL_LIMITATION,

    /**
     * The failure happened before any judgment could: malformed output, a schema violation, a
     * timeout, an exhausted retry. Not a classification error at all, and it must never be
     * counted as one — it is an availability problem wearing an accuracy problem's clothes.
     */
    PARSING_ERROR
}
