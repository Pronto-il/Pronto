package com.pronto.ai.dto;

import com.pronto.ai.taxonomy.Intent;
import com.pronto.ai.taxonomy.Urgency;

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
        String professionCode,
        String subcategoryCode,
        Intent intent,
        Urgency urgency,
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

    /**
     * The pre-taxonomy shape, with the four classification-layer fields left {@code null}.
     * See {@code ClassificationResponse}'s equivalent constructor for why this exists.
     */
    public ClassificationSuggestion(ClassificationStatus status, String detectedProfession, Long categoryId,
                                     String categoryCode, Double confidence, boolean lowConfidence,
                                     boolean unresolved, String ambiguityReason,
                                     List<CategoryCandidate> candidates,
                                     List<ClarificationQuestion> questions) {
        this(status, detectedProfession, null, null, null, null, categoryId, categoryCode, confidence,
                lowConfidence, unresolved, ambiguityReason, candidates, questions);
    }

    /**
     * Whether Pronto can currently dispatch the profession that was identified — the
     * <b>dispatch</b> question, kept deliberately separate from whether classification
     * succeeded.
     *
     * <p>{@code false} together with a populated {@code professionCode} is the defining shape of
     * a correct classification Pronto cannot serve. It says nothing about classification
     * quality, and the evaluation harness scores the two independently for exactly that reason.
     */
    public boolean isDispatchable() {
        return status != ClassificationStatus.UNSUPPORTED_PROFESSION && categoryCode != null;
    }
}
