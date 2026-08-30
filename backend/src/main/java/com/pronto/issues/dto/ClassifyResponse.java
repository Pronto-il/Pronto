package com.pronto.issues.dto;

import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.taxonomy.Intent;
import com.pronto.ai.taxonomy.Urgency;

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
 * <p><b>{@code professionCode}, {@code subcategoryCode}, {@code intent} and {@code urgency} are
 * additive and safe to ignore.</b> They are the structured form of what {@code
 * detectedProfession} already said in prose, from the versioned taxonomy: a machine-readable
 * answer for clients that want to branch on it (an emergency banner, a "we don't cover X yet"
 * screen that reads the code rather than string-matching Hebrew) rather than re-derive it. No
 * existing client reads them, none has to, and the four fields Pronto's own UI depends on are
 * unchanged in name, type and meaning.
 *
 * <p><b>{@code confidence} is still deliberately absent</b>, for the reason above: it is a real
 * number the customer cannot act on. It stays in the logs, the persisted telemetry and the
 * evaluation output, all of which read it. Adding the taxonomy fields was not an invitation to
 * reopen that decision.
 *
 * <p><b>{@code detectedProfession} is the one deliberate exception to that narrowing.</b> It is
 * Hebrew, it is customer-facing, and it is the entire content of the unsupported-profession
 * screen: "we don't cover that" is not an answer a customer can do anything with unless it names
 * what "that" was. It is populated on every status — the trade Pronto identified is meaningful
 * context regardless — but only rendered on the unsupported one.
 */
public record ClassifyResponse(ClassificationStatus status, String detectedProfession,
                                String professionCode, String subcategoryCode,
                                Intent intent, Urgency urgency,
                                Long suggestedCategoryId, String suggestedCategoryCode,
                                List<ClarifyQuestionResponse> questions) {
}
