package com.pronto.issues.dto;

import com.pronto.issues.entity.IssueUrgencyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Wire shape for {@code POST /api/issues}. See
 * {@code docs/architecture/api-contract-issues.md} §2.2.
 *
 * <p>{@code categoryId} remains whatever the customer confirmed or overrode — the AI's
 * suggestion never wins over an explicit customer choice at creation time.
 *
 * <p>{@code clarificationAnswers} carries the conversation from the {@code /classify} rounds
 * so it can finally be persisted. It used to be discarded at this boundary, which threw away
 * the most informative context in the whole flow: the answers are what the Professional Brief
 * is built from and what the professional reads next to the customer's own words. They are
 * customer-authored content, stored verbatim.
 *
 * <p>Still deliberately carries no AI confidence/candidate fields. Those are recorded
 * server-side after creation, where they can be vouched for, rather than accepted from a
 * client that could send anything.
 */
public record CreateIssueRequest(
        @NotNull Long categoryId,
        @NotBlank @Size(min = IssueText.DESCRIPTION_MIN_LENGTH, max = IssueText.DESCRIPTION_MAX_LENGTH)
        String description,
        @NotNull IssueUrgencyType urgencyType,
        @Size(max = 6) List<@NotBlank String> imageKeys,
        @Valid @Size(max = 3) List<ClarificationAnswerRequest> clarificationAnswers
) {
}
