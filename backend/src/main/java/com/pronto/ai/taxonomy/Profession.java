package com.pronto.ai.taxonomy;

import java.util.List;

/**
 * One trade in Pronto's classification label space, with its subcategories and — separately —
 * whether Pronto can currently dispatch it.
 *
 * <p><b>The two fields answer two different questions and must not be conflated.</b>
 * {@link #code()} is the answer to "what does this customer actually need?", decided from the
 * evidence alone. {@link #dispatchCategoryCode()} is the answer to "can Pronto serve that
 * today?", decided by the production {@code categories} table. A profession with no dispatch
 * category is a <em>correct classification</em> Pronto cannot act on, never a failed one — and
 * never a reason to substitute a different profession that happens to be dispatchable.
 *
 * @param code                 stable identifier, globally unique, e.g. {@code BOILER_TECHNICIAN}
 * @param nameHe               the Hebrew trade name as it is normally used in Israel
 * @param priorityRank         the source workbook's ordering, 1-based; lower is more common
 * @param dispatchCategoryCode the {@code categories.code} this profession is served by, or
 *                             {@code null} when Pronto does not currently dispatch it
 * @param subcategories        the concrete problems under this trade, in display order
 */
public record Profession(
        String code,
        String nameHe,
        int priorityRank,
        String dispatchCategoryCode,
        List<ProfessionSubcategory> subcategories
) {

    public Profession {
        subcategories = subcategories == null ? List.of() : List.copyOf(subcategories);
    }

    /**
     * Whether Pronto can currently send someone for this trade.
     *
     * <p>Deliberately derived from the presence of a mapping rather than stored as its own
     * flag: two fields that must agree are two fields that can disagree, and the mapping is
     * the thing that is actually true.
     */
    public boolean isDispatchable() {
        return dispatchCategoryCode != null && !dispatchCategoryCode.isBlank();
    }
}
