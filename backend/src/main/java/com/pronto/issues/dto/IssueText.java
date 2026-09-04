package com.pronto.issues.dto;

/**
 * Length bounds for the customer's own free-text description of a problem, shared by the two
 * request bodies that carry one — {@link ClassifyRequest} (no persistence) and
 * {@link CreateIssueRequest} (the commit).
 *
 * <p>They must agree. The customer types the description once and it travels through both calls,
 * so a bound applied to one and not the other would let a description be classified and then
 * refused at the moment of booking — the worst possible place to discover it.
 *
 * <p>The frontend mirrors these numbers in {@code shared/api/fieldLimits.ts} and stops the caret
 * at {@link #DESCRIPTION_MAX_LENGTH}, so the limit is normally reached in the input rather than
 * here. That is a courtesy, not the rule: this annotation is what actually enforces it, and it
 * holds for any caller, including one that never loaded the frontend.
 *
 * <p>{@code issues.description} is a {@code TEXT} column (V6), so nothing in the schema constrains
 * this — the DTO is the only bound there is.
 */
public final class IssueText {

    /** Short enough to be a typo, long enough to be a sentence — unchanged, pre-existing rule. */
    public static final int DESCRIPTION_MIN_LENGTH = 10;

    /**
     * A few sentences. Deliberately far below what the model could accept: the classifier does
     * better with a focused description than with an essay, and the customer is told the limit by
     * a counter as they type rather than by a rejection afterwards.
     */
    public static final int DESCRIPTION_MAX_LENGTH = 300;

    private IssueText() {
    }
}
