package com.pronto.ai.client;

import com.pronto.ai.dto.ClassificationStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default ({@code pronto.ai.mode=mock}) {@link AiClassificationClient} — no real OpenAI key
 * needed. A lightweight Hebrew keyword/substring heuristic against each seeded category,
 * falling back to {@code general_handyman} if nothing matches, per
 * {@code docs/architecture/api-contract-issues.md} §3.1. Every explanation is prefixed
 * {@code "[מוק]"} so it's never mistaken for a real AI response during manual QA — same
 * "does something observable/useful," not a pure no-op, spirit as
 * {@code auth.email.LoggingEmailSender}.
 *
 * <p><b>Ignores images entirely</b> — no vision capability needed/expected in mock mode
 * (§3.1), so the {@code images} parameter is unused.
 */
@Component
@ConditionalOnProperty(prefix = "pronto.ai", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockAiClassificationClient implements AiClassificationClient {

    private static final String FALLBACK_CATEGORY_CODE = "general_handyman";

    /**
     * First matching rule wins. Deliberately covers all 7 non-fallback seeded categories
     * (V10__seed_categories.sql) with a handful of representative Hebrew keywords each —
     * a judgment call, easy to extend, not meant to be an exhaustive NLP model.
     */
    private static final List<KeywordRule> RULES = List.of(
            new KeywordRule("plumbing",
                    List.of("מים", "נזיל", "נזילה", "ברז", "צנרת", "אינסטלצ", "ביוב", "סתימה", "דלף")),
            new KeywordRule("electrical",
                    List.of("חשמל", "קצר", "שקע", "מפסק", "נורה", "כבל חשמלי", "לוח חשמל")),
            new KeywordRule("ac_hvac",
                    List.of("מזגן", "מיזוג", "קירור", "חימום", "קונדנס", "מדחס")),
            new KeywordRule("appliance_repair",
                    List.of("כביסה", "מקרר", "תנור", "מדיח", "מייבש", "מוצר חשמלי", "מכשיר חשמלי")),
            new KeywordRule("locksmith",
                    List.of("מנעול", "מפתח נשבר", "דלת נעולה", "ננעל", "צילינדר")),
            new KeywordRule("carpentry",
                    List.of("נגרות", "ארון", "דלת עץ", "רהיט", "מגירה", "עץ שבור")),
            new KeywordRule("painting",
                    List.of("צבע", "צביעה", "קיר מתקלף", "טיח", "עובש בקיר"))
    );

    @Override
    public ClassificationResult classify(String description, List<ImageAttachment> images) {
        String text = description == null ? "" : description;

        for (KeywordRule rule : RULES) {
            for (String keyword : rule.keywords()) {
                if (text.contains(keyword)) {
                    String explanation = "[מוק] סיווג לפי מילת המפתח \"" + keyword
                            + "\" שזוהתה בתיאור, המתאימה לקטגוריה \"" + rule.categoryCode() + "\".";
                    // Fixed confidence for a deterministic keyword hit — a mock has no real
                    // notion of certainty; 0.7 is a judgment call, not a hard requirement.
                    return new ClassificationResult(ClassificationStatus.CLASSIFIED, rule.categoryCode(), 0.7,
                            explanation, List.of());
                }
            }
        }

        String explanation = "[מוק] לא זוהתה מילת מפתח מתאימה בתיאור; הוחזרה קטגוריית ברירת המחדל \"הנדימן כללי\".";
        return new ClassificationResult(ClassificationStatus.CLASSIFIED, FALLBACK_CATEGORY_CODE, null, explanation,
                List.of());
    }

    /**
     * Mock has no contradiction-detection/vision capability — {@link #classify} never
     * returns {@code QUESTIONS} in the first place (keyword heuristic only), so there is
     * nothing to reconcile here. {@code clarificationAnswers} is accepted (to satisfy the
     * interface contract) but ignored, same spirit as {@link #classify} ignoring images.
     */
    @Override
    public ClassificationResult classifyWithClarification(String description, List<ImageAttachment> images,
                                                            List<ClarificationAnswer> clarificationAnswers) {
        return classify(description, images);
    }

    private record KeywordRule(String categoryCode, List<String> keywords) {
    }
}
