package com.pronto.ai.dto;

import java.util.List;

/**
 * Output of {@code service.ClassificationService} — the application's routing decision for
 * one pass, after the AI response has been validated against the real category table and run
 * through {@code decision.RoutingDecisionPolicy}.
 *
 * <p>{@code status == QUESTIONS}: {@code categoryId}/{@code categoryCode} are {@code null}
 * and {@code questions} holds exactly one high-value question (Pronto asks iteratively and
 * re-evaluates after each answer, rather than firing a batch of questions at the customer).
 *
 * <p>{@code status == CLASSIFIED}: a real {@code categories} row is resolved and
 * {@code questions} is empty. Two flags describe <i>how</i> that decision was reached, and
 * they are deliberately not interchangeable:
 * <ul>
 *   <li>{@code lowConfidence} — Pronto committed to a specialist it believes is right while
 *       recording that it was not fully confident. The category is a real prediction.</li>
 *   <li>{@code unresolved} — Pronto ran out of questions with two materially different
 *       categories still live (or with nothing that survived validation), so
 *       {@code categoryCode} is the {@code general_handyman} fallback rather than a
 *       prediction. Anything measuring routing accuracy must treat this differently from a
 *       committed answer, or improvement will look like progress when it is really just
 *       traffic diverted to the fallback.</li>
 * </ul>
 * An {@code unresolved} result is always also {@code lowConfidence}; the reverse does not hold.
 *
 * <p>{@code status == UNSUPPORTED_PROFESSION}: Pronto identified the trade and does not offer
 * it. {@code categoryId}/{@code categoryCode} are {@code null}, {@code questions} is empty, and
 * {@code detectedProfession} carries the trade name shown to the customer. Neither
 * {@code lowConfidence} nor {@code unresolved} is set — this is a successful classification whose
 * only property is that Pronto cannot serve it, and marking it low-confidence would corrupt the
 * one metric that tells a hard routing case from an out-of-catalogue one.
 *
 * <p>{@code candidates}/{@code ambiguityReason}/{@code confidence} are internal diagnostics:
 * persisted and logged, deliberately not forwarded to the customer-facing response.
 * {@code detectedProfession} is the exception — it IS customer-facing, and only in the
 * unsupported case, because "we don't cover X" is unhelpful without naming X.
 */
public record ClassificationSuggestion(
        ClassificationStatus status,
        String detectedProfession,
        Long categoryId,
        String categoryCode,
        Double confidence,
        boolean lowConfidence,
        boolean unresolved,
        String ambiguityReason,
        List<CategoryCandidate> candidates,
        List<ClarificationQuestion> questions
) {

    public ClassificationSuggestion {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        questions = questions == null ? List.of() : List.copyOf(questions);
    }
}
