package com.pronto.ai.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One labelled routing case. See {@code src/test/resources/ai-eval/cases.json}.
 *
 * @param id                   stable identifier, used in the confusion matrix and failure list
 * @param description          the customer text, exactly as they would type it
 * @param selectedCategory     the category the customer picked, or {@code null}. Present
 *                             specifically so "customer chose the wrong trade" can be
 *                             measured rather than assumed.
 * @param clarificationAnswers scripted answers, keyed by a substring expected to appear in the
 *                             generated question. The harness cannot know in advance what will
 *                             be asked, so matching is by keyword; an unmatched question falls
 *                             back to the "not sure" option and is reported, which is itself a
 *                             useful signal about the dataset.
 * @param expectedCategory     the ground-truth {@code categories.code} Pronto should route to, or
 *                             the sentinel {@link #EXPECTED_UNSUPPORTED} for a case whose correct
 *                             answer is "Pronto does not cover this trade at all". A sentinel
 *                             rather than {@code null}, deliberately: {@code null} is what a case
 *                             with a FORGOTTEN label also looks like, and the two must not be
 *                             indistinguishable in a file that defines ground truth.
 * @param notes                why this case is in the set — usually which overlap it probes
 * @param tier                 {@code core} for the approved regression set the 95% target is
 *                             measured on, {@code challenge} for deliberately adversarial or
 *                             multi-trade cases reported separately. Absent is treated as
 *                             {@code core}, so a case added without thinking about tiers
 *                             counts towards the target rather than quietly escaping it.
 * @param expectsClarification {@code true} for a case whose description is deliberately
 *                             insufficient to separate two trades, so committing without
 *                             asking is wrong <i>however</i> the guess lands. {@code null}
 *                             (the default) means the case makes no claim either way, which is
 *                             right for most cases — plenty of descriptions are answerable
 *                             directly and demanding a question would be its own defect.
 * @param imageKeys            reserved for image-based cases. Empty today: the harness passes
 *                             the list straight through, so adding real keys later needs no
 *                             harness change, only fixtures.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationCase(
        String id,
        String description,
        String selectedCategory,
        Map<String, String> clarificationAnswers,
        String expectedCategory,
        String notes,
        String tier,
        Boolean expectsClarification,
        List<String> imageKeys
) {

    /**
     * Ground-truth label for "the correct answer is that Pronto does not offer this trade".
     *
     * <p>Not a category code and never matched against one — {@code ServiceCategoryCatalog} would
     * not resolve it, which is exactly the property that keeps it from being mistaken for a real
     * routing target if it ever leaked out of the dataset.
     */
    public static final String EXPECTED_UNSUPPORTED = "unsupported";

    /** True when this case's correct outcome is the unsupported-profession state. */
    public boolean expectsUnsupported() {
        return EXPECTED_UNSUPPORTED.equalsIgnoreCase(expectedCategory);
    }

    public static final String TIER_CORE = "core";
    public static final String TIER_CHALLENGE = "challenge";

    public EvaluationCase {
        clarificationAnswers = clarificationAnswers == null ? Map.of() : Map.copyOf(clarificationAnswers);
        imageKeys = imageKeys == null ? List.of() : List.copyOf(imageKeys);
        tier = tier == null || tier.isBlank() ? TIER_CORE : tier.trim();
    }

    public boolean isCore() {
        return TIER_CORE.equalsIgnoreCase(tier);
    }

    /** Whether this case asserts that a clarification question is the only correct behaviour. */
    public boolean requiresClarification() {
        return Boolean.TRUE.equals(expectsClarification);
    }
}
