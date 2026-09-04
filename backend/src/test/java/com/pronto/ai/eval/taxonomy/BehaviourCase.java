package com.pronto.ai.eval.taxonomy;

import java.util.List;

/**
 * One named behavioural expectation about the classifier, of the kind the taxonomy redesign was
 * specified in: "a burst pipe is an emergency", "a fresh damp patch is not the painter's job".
 *
 * <p><b>Deliberately separate from {@link TaxonomyEvaluationCase}.</b> The 5,000-row dataset
 * makes exactly one kind of claim — this description has this label — and its flat shape is what
 * lets it be generated, split and scored mechanically. These cases make several different kinds
 * of claim, and the most valuable ones are <em>negative</em>: "must not be PAINTER" is the whole
 * content of the painter guard, and there is no column in the dataset that can express it.
 * Forcing both into one schema would either lose the negatives or bloat 5,000 rows with columns
 * that are null in all of them.
 *
 * <p>They also carry a {@code name} rather than a numeric ID, because a failure here should read
 * as "painter-guard failed", not as "case 4133 failed".
 *
 * @param name                 short identifier used in the report
 * @param description          the customer's Hebrew text
 * @param expectedProfession   the profession that must be chosen, or {@code null} to assert
 *                             nothing about which one it is
 * @param expectedSubcategory  the subcategory that must be chosen, or {@code null}
 * @param forbiddenProfessions professions that must NOT be chosen. The guard cases — where the
 *                             point is what the classifier must not do, and several different
 *                             right answers exist.
 * @param expectedIntent       required intent, or {@code null}
 * @param minimumUrgency       the lowest urgency that counts as correct, or {@code null}. A range
 *                             rather than a value on purpose: for a flooding kitchen both HIGH and
 *                             CRITICAL are defensible, and pinning one would fail a correct answer.
 * @param mustAsk              {@code TRUE} the description is too ambiguous to route and a
 *                             question is the only correct behaviour; {@code FALSE} it is clear
 *                             and asking is itself a defect; {@code null} no claim either way
 * @param rationale            why this case exists — read when it fails
 */
public record BehaviourCase(
        String name,
        String description,
        String expectedProfession,
        String expectedSubcategory,
        List<String> forbiddenProfessions,
        String expectedIntent,
        String minimumUrgency,
        Boolean mustAsk,
        String rationale
) {

    public BehaviourCase {
        forbiddenProfessions = forbiddenProfessions == null ? List.of() : List.copyOf(forbiddenProfessions);
    }

    /** Urgency ordering, for {@link #minimumUrgency}. */
    public static int urgencyRank(String urgency) {
        if (urgency == null) {
            return -1;
        }
        return switch (urgency.toUpperCase(java.util.Locale.ROOT)) {
            case "LOW" -> 0;
            case "NORMAL" -> 1;
            case "HIGH" -> 2;
            case "CRITICAL" -> 3;
            default -> -1;
        };
    }
}
