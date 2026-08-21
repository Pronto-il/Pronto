package com.pronto.ai.dto;

/**
 * One category the model considers plausible for this issue, with its self-reported
 * confidence.
 *
 * <p>The candidate list is what lets the application reason about ambiguity instead of
 * delegating that judgment entirely to the model: {@code decision.RoutingDecisionPolicy}
 * looks at how close the top two candidates are and how many remain plausible, not just at
 * the winner's confidence. See {@code docs/architecture/ai-issue-classification-design.md}.
 *
 * @param categoryCode a real {@code categories.code} — validated against
 *                     {@code catalog.ServiceCategoryCatalog} before this record is trusted
 * @param confidence   0..1, the model's self-report; explicitly <b>not</b> a calibrated
 *                     probability (see {@code RoutingDecisionPolicy}'s Javadoc)
 */
public record CategoryCandidate(String categoryCode, double confidence) {
}
