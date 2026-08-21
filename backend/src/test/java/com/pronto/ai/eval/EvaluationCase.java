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
 * @param expectedCategory     the ground-truth {@code categories.code} Pronto should route to
 * @param notes                why this case is in the set — usually which overlap it probes
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
        List<String> imageKeys
) {

    public EvaluationCase {
        clarificationAnswers = clarificationAnswers == null ? Map.of() : Map.copyOf(clarificationAnswers);
        imageKeys = imageKeys == null ? List.of() : List.copyOf(imageKeys);
    }
}
