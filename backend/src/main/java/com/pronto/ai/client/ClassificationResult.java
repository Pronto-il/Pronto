package com.pronto.ai.client;

/**
 * Raw result from an {@link AiClassificationClient} — a {@code categories.code}, not yet
 * resolved to a row (that's {@code service.ClassificationService}'s job, including the
 * fallback-to-{@code general_handyman} logic if {@code categoryCode} doesn't match any
 * seeded category). {@code confidence} is nullable — see
 * {@code docs/architecture/api-contract-issues.md} §2.1's response field notes.
 */
public record ClassificationResult(String categoryCode, Double confidence, String explanation) {
}
