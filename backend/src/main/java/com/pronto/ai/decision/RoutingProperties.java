package com.pronto.ai.decision;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Every threshold the routing decision depends on, in one place and overridable per
 * environment ({@code pronto.ai.routing.*} in {@code application.yml}) — deliberately not
 * magic numbers scattered through {@link RoutingDecisionPolicy}.
 *
 * <p>The defaults below are starting points chosen to be tuned against the labelled
 * evaluation harness ({@code src/test/java/com/pronto/ai/eval}), not values anyone has
 * measured as optimal. Tuning them is the intended way to trade clarification rate against
 * final routing accuracy.
 */
@Component
@ConfigurationProperties(prefix = "pronto.ai.routing")
public class RoutingProperties {

    /**
     * Hard cap on clarification questions per issue, across all rounds. This is what makes an
     * endless clarification loop structurally impossible: the budget is derived from the
     * number of answers already supplied, so a model that keeps asking simply runs out.
     */
    private int maxClarificationQuestions = 2;

    /**
     * Below this, the top candidate is not considered self-evidently safe on its own. Never a
     * sufficient reason to ask a question by itself — see {@link RoutingDecisionPolicy}.
     */
    private double minConfidence = 0.70;

    /**
     * Minimum gap between the top two candidates. A smaller gap means two categories are
     * genuinely competing, which is a real ambiguity regardless of the absolute confidence.
     */
    private double minCandidateMargin = 0.15;

    /** A candidate at or above this confidence still counts as "reasonably plausible". */
    private double plausibleCandidateConfidence = 0.20;

    /**
     * Used by the evaluation harness to classify a wrong prediction as a
     * "high-confidence wrong classification" — the most dangerous failure mode, since it
     * routes the customer to the wrong trade without ever asking.
     */
    private double highConfidence = 0.85;

    public int getMaxClarificationQuestions() {
        return maxClarificationQuestions;
    }

    public void setMaxClarificationQuestions(int maxClarificationQuestions) {
        this.maxClarificationQuestions = maxClarificationQuestions;
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public double getMinCandidateMargin() {
        return minCandidateMargin;
    }

    public void setMinCandidateMargin(double minCandidateMargin) {
        this.minCandidateMargin = minCandidateMargin;
    }

    public double getPlausibleCandidateConfidence() {
        return plausibleCandidateConfidence;
    }

    public void setPlausibleCandidateConfidence(double plausibleCandidateConfidence) {
        this.plausibleCandidateConfidence = plausibleCandidateConfidence;
    }

    public double getHighConfidence() {
        return highConfidence;
    }

    public void setHighConfidence(double highConfidence) {
        this.highConfidence = highConfidence;
    }
}
