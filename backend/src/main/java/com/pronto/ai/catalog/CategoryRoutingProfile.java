package com.pronto.ai.catalog;

import java.util.List;

/**
 * The routing boundary for one Pronto service category — the "who does Pronto send, and who
 * does it deliberately NOT send" definition that goes into the classification prompt.
 *
 * <p>Deliberately not a duplicated taxonomy: {@code code} must match a real
 * {@code categories.code} row, and {@link CategoryRoutingProfiles} is joined against the DB
 * rows by {@link ServiceCategoryCatalog}. A profile whose code no longer exists in the
 * database is dropped; a database category with no profile still participates in routing,
 * just with its name alone and no boundary text.
 *
 * @param code          the {@code categories.code} this profile describes
 * @param scope         one-line statement of which physical system/component this
 *                      professional is responsible for
 * @param belongs       symptoms/jobs that route here
 * @param doesNotBelong symptoms/jobs that look like they route here but do not
 * @param components    typical components this professional services (helps the model reason
 *                      about "which system is being serviced" rather than keyword-matching)
 * @param confusedWith  the other category codes this one is most often confused with, each
 *                      paired with the rule that resolves the overlap
 */
public record CategoryRoutingProfile(
        String code,
        String scope,
        List<String> belongs,
        List<String> doesNotBelong,
        List<String> components,
        List<OverlapRule> confusedWith
) {

    /**
     * One "X vs Y" disambiguation rule, rendered into the prompt under the owning category.
     *
     * @param otherCategoryCode the competing {@code categories.code}
     * @param resolution        how to decide between the two, including when neither can be
     *                          established from the evidence (which is a clarification case,
     *                          not a coin flip)
     */
    public record OverlapRule(String otherCategoryCode, String resolution) {
    }
}
