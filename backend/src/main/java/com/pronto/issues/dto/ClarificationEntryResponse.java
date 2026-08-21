package com.pronto.issues.dto;

/**
 * One question/answer pair from the clarification conversation, as returned by
 * {@code GET /api/issues/{id}}.
 *
 * <p>Visible to both parties: to the customer it is a record of what they were asked, and to
 * the professional it is customer-supplied fact, not AI inference — which is why it is
 * rendered next to the customer's own description rather than inside the Pronto analysis
 * section.
 */
public record ClarificationEntryResponse(String question, String answer) {
}
