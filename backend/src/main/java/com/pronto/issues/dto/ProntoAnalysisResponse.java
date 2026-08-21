package com.pronto.issues.dto;

import com.pronto.issues.entity.IssueBriefStatus;

import java.util.List;

/**
 * Pronto's Professional Brief, as the professional's app receives it on
 * {@code GET /api/issues/{id}}.
 *
 * <p>Named "analysis", not "description", on purpose: this is the AI's interpretation and it
 * is returned as a separate object from {@code IssueDetailResponse.description}, which is and
 * remains the customer's own untouched words. The separation is structural, not just a UI
 * convention — nothing here can be mistaken for something the customer said.
 *
 * <p>Returned only to a professional with an order on the issue. {@code status} lets the
 * client distinguish "still generating" from "generation failed" without inspecting fields
 * for null; both are non-blocking states.
 *
 * <p>Every list may legitimately be empty. Empty {@code recommendedParts} means the evidence
 * did not identify a part worth bringing, which is more useful than a padded guess.
 */
public record ProntoAnalysisResponse(
        IssueBriefStatus status,
        String customerProblemSummary,
        String clarificationSummary,
        List<String> imageObservations,
        LikelyIssueResponse likelyIssue,
        List<String> possibleCauses,
        List<String> recommendedTools,
        List<String> recommendedParts,
        List<String> safetyNotes
) {

    /**
     * The evidence-backed hypothesis. {@code null} when the model produced none, or when its
     * hypothesis arrived without supporting evidence and was dropped in validation — an
     * unexplained guess is not shown to a professional.
     */
    public record LikelyIssueResponse(String description, Double confidence, List<String> evidence) {
    }
}
