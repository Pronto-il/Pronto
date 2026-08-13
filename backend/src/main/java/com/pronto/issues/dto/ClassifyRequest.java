package com.pronto.issues.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Wire shape for {@code POST /api/issues/classify}. See
 * {@code docs/architecture/api-contract-issues.md} §2.1. {@code imageKeys} entries are
 * further validated (existence + ownership, §3.3) in {@code IssuesService} — Bean
 * Validation only covers shape (non-blank entries, at most 6).
 */
public record ClassifyRequest(
        @NotBlank @Size(min = 10, max = 2000) String description,
        @Size(max = 6) List<@NotBlank String> imageKeys
) {
}
