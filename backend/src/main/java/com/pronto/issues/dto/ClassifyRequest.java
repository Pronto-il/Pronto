package com.pronto.issues.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Wire shape for {@code POST /api/issues/classify}. See
 * {@code docs/architecture/api-contract-issues.md} §2.1. {@code imageKeys} entries are
 * further validated (existence + ownership, §3.3) in {@code IssuesService} — Bean
 * Validation only covers shape (non-blank entries, at most 6).
 *
 * <p>{@code clarificationAnswers} is the second-call shape of this same endpoint (§2.1's
 * clarification-question extension): omitted/empty for the initial classification pass;
 * when present, {@code description}/{@code imageKeys} must be the same original values from
 * the first call, and the backend performs exactly one final classification instead of a
 * fresh initial one — see {@code IssuesService.classify}.
 */
public record ClassifyRequest(
        @NotBlank @Size(min = 10, max = 2000) String description,
        @Size(max = 6) List<@NotBlank String> imageKeys,
        @Valid @Size(max = 3) List<ClarificationAnswerRequest> clarificationAnswers
) {
}
