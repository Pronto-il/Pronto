package com.pronto.issues.dto;

import com.pronto.ai.dto.ClassificationStatus;

import java.util.List;

/**
 * Response body for {@code POST /api/issues/classify}. See
 * {@code docs/architecture/api-contract-issues.md} §2.1.
 *
 * <p>{@code status = CLASSIFIED}: {@code suggestedCategoryId}/{@code suggestedCategoryCode}
 * are populated and {@code questions} is empty.
 *
 * <p>{@code status = UNSUPPORTED_PROFESSION}: Pronto identified the trade the customer needs
 * and does not offer it. The suggestion fields are {@code null}, {@code questions} is empty, and
 * {@code detectedProfession} names the trade — the client shows a dedicated screen rather than
 * continuing to matching. This is a successful classification with no way forward, not a failure
 * and not an empty result set.
 *
 * <p>{@code status = QUESTIONS}: the suggestion fields are {@code null} and
 * {@code questions} holds exactly one question. Pronto asks one question at a time and
 * re-classifies after each answer, so the client may receive this status again on the next
 * call — until the server-side clarification budget is spent, at which point a
 * {@code CLASSIFIED} result is guaranteed.
 *
 * <p><b>Carries no confidence, candidates, ambiguity reason or model reasoning.</b> Those are
 * real and are persisted and logged, but they are internal diagnostics: a customer benefits
 * from a good question and a correct category, not from a probability they cannot act on.
 * This is a deliberate narrowing of the previous shape, which shipped {@code confidence} and
 * an English-only {@code explanation} to a Hebrew-only UI that was documented as never
 * allowed to render either.
 *
 * <p><b>{@code detectedProfession} is the one deliberate exception to that narrowing.</b> It is
 * Hebrew, it is customer-facing, and it is the entire content of the unsupported-profession
 * screen: "we don't cover that" is not an answer a customer can do anything with unless it names
 * what "that" was. It is populated on every status — the trade Pronto identified is meaningful
 * context regardless — but only rendered on the unsupported one.
 */
public record ClassifyResponse(ClassificationStatus status, String detectedProfession,
                                Long suggestedCategoryId, String suggestedCategoryCode,
                                List<ClarifyQuestionResponse> questions) {
}
