package com.pronto.issues.dto;

import com.pronto.issues.entity.IssueUrgencyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Wire shape for {@code POST /api/issues}. See
 * {@code docs/architecture/api-contract-issues.md} §2.2. Deliberately carries no
 * {@code suggestedCategoryId}/AI-explanation field — the AI suggestion is fully discarded
 * once the customer confirms/overrides it (§2.2's main decision, flagged for user sign-off
 * in the contract doc — not silently settled).
 */
public record CreateIssueRequest(
        @NotNull Long categoryId,
        @NotBlank @Size(min = 10, max = 2000) String description,
        @NotNull IssueUrgencyType urgencyType,
        @Size(max = 6) List<@NotBlank String> imageKeys
) {
}
