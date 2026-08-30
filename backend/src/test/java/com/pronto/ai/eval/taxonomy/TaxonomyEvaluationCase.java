package com.pronto.ai.eval.taxonomy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One labelled row of the classification workbook, as converted by
 * {@code backend/tools/classification_dataset/build_dataset.py}.
 *
 * <p>Field names match the JSONL exactly; the JSONL's field names in turn match the workbook's
 * columns, so a value can be followed from a spreadsheet cell to an evaluation result without a
 * translation step in between.
 *
 * @param id                          the workbook's own {@code ID} column, preserved verbatim.
 *                                    <b>This is the traceability guarantee</b>: every number the
 *                                    harness prints can be taken back to the exact spreadsheet
 *                                    row that produced it.
 * @param description                 the customer text, exactly as the workbook has it —
 *                                    Hebrew, often slangy, misspelled or clipped, which is the
 *                                    point
 * @param expectedProfession          ground-truth profession code
 * @param expectedSubcategory         ground-truth subcategory code, valid under that profession
 * @param expectedIntent              ground-truth {@code Intent} name
 * @param expectedUrgency             ground-truth {@code Urgency} name
 * @param expectedNeedsClarification  whether the workbook considers this description too
 *                                    ambiguous to route without asking
 * @param expectedDispatchCategory    the {@code categories.code} the expected profession is
 *                                    dispatched under, or {@code null} when Pronto does not
 *                                    dispatch it. <b>Derived from the taxonomy, not labelled by a
 *                                    human</b> — it is a fact about Pronto's catalogue rather than
 *                                    about this description, and it is deliberately not part of
 *                                    classification scoring.
 * @param descriptionStyle            the workbook's phrasing style for this row ("סלנג",
 *                                    "שגיאת כתיב קלה", ...). Carried so accuracy can be broken
 *                                    down by how the customer wrote, which is where a classifier
 *                                    that only understands tidy input shows up.
 * @param edgeCase                    the workbook's own edge-case flag
 * @param evalType                    {@code STANDARD}, {@code EDGE_CASE} or {@code AMBIGUOUS}
 * @param split                       {@code dev}, {@code validation} or {@code holdout} — frozen
 *                                    by the converter, never chosen at runtime
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaxonomyEvaluationCase(
        int id,
        String description,
        String expectedProfession,
        String expectedSubcategory,
        String expectedIntent,
        String expectedUrgency,
        boolean expectedNeedsClarification,
        String expectedDispatchCategory,
        String descriptionStyle,
        boolean edgeCase,
        String evalType,
        String split
) {

    public static final String SPLIT_DEV = "dev";
    public static final String SPLIT_VALIDATION = "validation";
    public static final String SPLIT_HOLDOUT = "holdout";

    /** True when Pronto currently dispatches the ground-truth profession. */
    public boolean expectsDispatchable() {
        return expectedDispatchCategory != null && !expectedDispatchCategory.isBlank();
    }
}
