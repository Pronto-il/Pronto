package com.pronto.ai.dto;

import java.util.List;

/**
 * Pronto's preparation brief for the professional who may accept the job — the second of this
 * package's two AI response models, generated in its own call after routing is final.
 *
 * <p>This is <b>Pronto's analysis</b>, and it is kept strictly separate from the customer's
 * own report at every layer: the customer's {@code issues.description} is never overwritten
 * or rewritten, {@link #customerProblemSummary} is a summary Pronto authored (not a quote
 * attributed to the customer), and the professional-facing API/UI renders the two under
 * different headings.
 *
 * <p>Every list may legitimately be empty — an empty {@link #recommendedParts} is a correct
 * answer when the evidence does not identify a part, and is preferred over a generic
 * toolbox dump.
 *
 * @param customerProblemSummary neutral one-or-two-sentence restatement of the problem
 * @param clarificationSummary   what the clarification answers established, or {@code null}
 *                               when no questions were asked
 * @param imageObservations      what is genuinely visible in the photos — observations, not
 *                               diagnoses, and never claims about hidden/internal parts
 * @param likelyIssue            the evidence-backed hypothesis (may be {@code null} only if
 *                               validation had to strip it)
 * @param possibleCauses         other plausible causes worth having in mind
 * @param recommendedTools       tools that fit this specific issue and this category
 * @param recommendedParts       common parts/consumables worth bringing; generic, never
 *                               model numbers or proprietary parts unless the customer
 *                               genuinely identified them
 * @param safetyNotes            concise preparation/safety notes, only when actually relevant
 */
public record ProfessionalBriefResponse(
        String customerProblemSummary,
        String clarificationSummary,
        List<String> imageObservations,
        LikelyIssue likelyIssue,
        List<String> possibleCauses,
        List<String> recommendedTools,
        List<String> recommendedParts,
        List<String> safetyNotes
) {

    public ProfessionalBriefResponse {
        imageObservations = imageObservations == null ? List.of() : List.copyOf(imageObservations);
        possibleCauses = possibleCauses == null ? List.of() : List.copyOf(possibleCauses);
        recommendedTools = recommendedTools == null ? List.of() : List.copyOf(recommendedTools);
        recommendedParts = recommendedParts == null ? List.of() : List.copyOf(recommendedParts);
        safetyNotes = safetyNotes == null ? List.of() : List.copyOf(safetyNotes);
    }
}
