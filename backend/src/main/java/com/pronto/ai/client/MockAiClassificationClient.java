package com.pronto.ai.client;

import com.pronto.ai.catalog.CategoryRoutingProfiles;
import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationRequest;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.ai.dto.LikelyIssue;
import com.pronto.ai.dto.ProfessionalBriefRequest;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default ({@code pronto.ai.mode=mock}) client — no OpenAI key needed, no network, fully
 * deterministic.
 *
 * <p>A Hebrew keyword heuristic scores every seeded category, and those scores become a real
 * candidate list. That matters beyond convenience: because the mock emits candidates and
 * confidences in the same shape OpenAI does, the whole routing pipeline —
 * {@code decision.RoutingDecisionPolicy}'s ambiguity detection, the clarification budget,
 * duplicate suppression, the low-confidence fallback — is exercised in mock mode and in
 * tests, not only in production.
 *
 * <p>When two categories score close together it returns a clarification question, so the
 * iterative clarification flow is reachable locally. The answer is folded back into the
 * scoring text on the next round, which is what lets the loop actually resolve.
 *
 * <p><b>Ignores images entirely</b> — no vision capability is expected in mock mode
 * ({@code docs/architecture/api-contract-issues.md} §3.1). Every customer-visible string is
 * prefixed {@code [מוק]} so it is never mistaken for real AI output during manual QA, same
 * spirit as {@code auth.email.LoggingEmailSender}.
 */
@Component
@ConditionalOnProperty(prefix = "pronto.ai", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockAiClassificationClient implements AiClassificationClient {

    private static final String MOCK_PREFIX = "[מוק] ";

    /** Score gap below which the mock treats two categories as genuinely competing. */
    private static final double AMBIGUITY_GAP = 0.15;

    /**
     * Specialist precedence, preserved from the original first-match-wins rule but expressed
     * as a weight so it survives the move to scoring: a leak, a sparking outlet, an AC fault,
     * an appliance fault or a lock failure must never be swallowed by the handyman keywords
     * just because the customer also called it a small job.
     */
    private static final double SPECIALIST_WEIGHT = 1.0;
    private static final double HANDYMAN_WEIGHT = 0.5;

    /**
     * Covers every seeded category (V10__seed_categories.sql, as amended by
     * V31__replace_carpentry_with_handyman.sql — Carpentry was folded into Handyman) with a
     * handful of representative Hebrew keywords each: a judgment call, easy to extend, not
     * meant to be an exhaustive NLP model. {@code general_handyman} additionally remains the
     * fallback when nothing matches at all.
     */
    private static final List<KeywordRule> RULES = List.of(
            new KeywordRule(CategoryRoutingProfiles.CODE_PLUMBING, SPECIALIST_WEIGHT,
                    List.of("מים", "נזיל", "נזילה", "ברז", "צנרת", "אינסטלצ", "ביוב", "סתימה", "דלף",
                            "אסלה", "כיור", "דוד", "מקלחת", "ניקוז")),
            new KeywordRule(CategoryRoutingProfiles.CODE_ELECTRICAL, SPECIALIST_WEIGHT,
                    List.of("חשמל", "קצר", "שקע", "מפסק", "נורה", "כבל חשמלי", "לוח חשמל", "נתיך",
                            "תאורה", "פחת")),
            new KeywordRule(CategoryRoutingProfiles.CODE_AC_HVAC, SPECIALIST_WEIGHT,
                    List.of("מזגן", "מיזוג", "קירור", "מאייד", "מעבה", "קונדנס", "מדחס", "גז קירור")),
            new KeywordRule(CategoryRoutingProfiles.CODE_APPLIANCE_REPAIR, SPECIALIST_WEIGHT,
                    List.of("כביסה", "מקרר", "תנור", "מדיח", "מייבש", "כיריים", "מיקרוגל",
                            "מוצר חשמלי", "מכשיר חשמלי")),
            new KeywordRule(CategoryRoutingProfiles.CODE_LOCKSMITH, SPECIALIST_WEIGHT,
                    List.of("מנעול", "מפתח נשבר", "דלת נעולה", "ננעל", "צילינדר", "פריצה")),
            new KeywordRule(CategoryRoutingProfiles.CODE_PAINTING, SPECIALIST_WEIGHT,
                    List.of("צבע", "צביעה", "קיר מתקלף", "טיח", "שפכטל", "עובש בקיר")),
            new KeywordRule(CategoryRoutingProfiles.CODE_GENERAL_HANDYMAN, HANDYMAN_WEIGHT,
                    List.of("לתלות", "תלייה", "תליית", "מדף", "מדפים", "טלוויזיה", "להרכיב",
                            "הרכבת", "ארון", "רהיט", "מגירה", "ציר", "צירים", "ידית", "ידיות",
                            "וילון", "הנדימן"))
    );

    /**
     * Canned Hebrew questions for the overlaps that actually matter, keyed by the competing
     * pair. Mirrors the real disambiguation rules in {@code catalog.CategoryRoutingProfiles}
     * so the mock asks something plausible rather than filler. A pair with no entry here
     * simply does not trigger a question — the policy then commits, which is the safe
     * direction.
     */
    private static final Map<String, ClarificationQuestion> OVERLAP_QUESTIONS = Map.of(
            pairKey(CategoryRoutingProfiles.CODE_PLUMBING, CategoryRoutingProfiles.CODE_AC_HVAC),
            new ClarificationQuestion("mock-water-source", MOCK_PREFIX + "מאיפה מגיעים המים?",
                    List.of("מהמזגן או מתחתיו", "מצינור, ברז או קיר", "אני לא בטוח/ה"),
                    List.of(CategoryRoutingProfiles.CODE_AC_HVAC, CategoryRoutingProfiles.CODE_PLUMBING)),

            pairKey(CategoryRoutingProfiles.CODE_ELECTRICAL, CategoryRoutingProfiles.CODE_AC_HVAC),
            new ClarificationQuestion("mock-breaker-scope", MOCK_PREFIX + "המפסק קופץ גם בלי המזגן?",
                    List.of("רק כשהמזגן פועל", "גם עם מכשירים אחרים", "אני לא בטוח/ה"),
                    List.of(CategoryRoutingProfiles.CODE_AC_HVAC, CategoryRoutingProfiles.CODE_ELECTRICAL)),

            pairKey(CategoryRoutingProfiles.CODE_PLUMBING, CategoryRoutingProfiles.CODE_APPLIANCE_REPAIR),
            new ClarificationQuestion("mock-appliance-water", MOCK_PREFIX + "המים מגיעים מהמכשיר עצמו?",
                    List.of("מהמכשיר עצמו", "מהברז או מחיבור הניקוז בקיר", "אני לא בטוח/ה"),
                    List.of(CategoryRoutingProfiles.CODE_APPLIANCE_REPAIR, CategoryRoutingProfiles.CODE_PLUMBING)),

            pairKey(CategoryRoutingProfiles.CODE_ELECTRICAL, CategoryRoutingProfiles.CODE_APPLIANCE_REPAIR),
            new ClarificationQuestion("mock-appliance-power", MOCK_PREFIX + "מכשירים נוספים מושפעים מהתקלה?",
                    List.of("רק המכשיר הזה", "גם מכשירים אחרים", "אני לא בטוח/ה"),
                    List.of(CategoryRoutingProfiles.CODE_APPLIANCE_REPAIR, CategoryRoutingProfiles.CODE_ELECTRICAL)),

            pairKey(CategoryRoutingProfiles.CODE_LOCKSMITH, CategoryRoutingProfiles.CODE_GENERAL_HANDYMAN),
            new ClarificationQuestion("mock-door-fault", MOCK_PREFIX + "מה בדיוק לא עובד בדלת?",
                    List.of("המנעול או המפתח", "הדלת עצמה נתקעת או משתפשפת", "אני לא בטוח/ה"),
                    List.of(CategoryRoutingProfiles.CODE_LOCKSMITH, CategoryRoutingProfiles.CODE_GENERAL_HANDYMAN))
    );

    private final ServiceCategoryCatalog catalog;

    public MockAiClassificationClient(ServiceCategoryCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public ClassificationResponse classify(ClassificationRequest request) {
        List<String> availableCodes = catalog.categories().stream().map(ServiceCategory::code).toList();
        List<CategoryCandidate> candidates = score(scoringText(request), availableCodes);

        if (candidates.isEmpty()) {
            return new ClassificationResponse(ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE, 0.3, false,
                    "Mock heuristic matched no keyword.",
                    List.of(new CategoryCandidate(ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE, 0.3)), null);
        }

        CategoryCandidate top = candidates.get(0);
        boolean competing = candidates.size() >= 2
                && top.confidence() - candidates.get(1).confidence() < AMBIGUITY_GAP;

        ClarificationQuestion question = null;
        if (competing && request.mayAskQuestion()) {
            question = OVERLAP_QUESTIONS.get(pairKey(top.categoryCode(), candidates.get(1).categoryCode()));
        }

        String ambiguityReason = competing
                ? "Mock heuristic scored " + top.categoryCode() + " and " + candidates.get(1).categoryCode()
                        + " almost equally."
                : null;

        return new ClassificationResponse(top.categoryCode(), top.confidence(), question != null,
                ambiguityReason, candidates, question);
    }

    @Override
    public ProfessionalBriefResponse generateBrief(ProfessionalBriefRequest request) {
        String description = request.description() == null ? "" : request.description().trim();

        List<String> evidence = new ArrayList<>();
        if (!description.isEmpty()) {
            evidence.add(MOCK_PREFIX + "הלקוח כתב: " + truncate(description));
        }
        request.priorExchanges().forEach(exchange ->
                evidence.add(MOCK_PREFIX + exchange.question() + " → " + exchange.answer()));

        String clarificationSummary = request.priorExchanges().isEmpty() ? null
                : MOCK_PREFIX + "הלקוח ענה על " + request.priorExchanges().size() + " שאלות הבהרה.";

        return new ProfessionalBriefResponse(
                MOCK_PREFIX + (description.isEmpty() ? "לא סופק תיאור." : truncate(description)),
                clarificationSummary,
                // No vision in mock mode: photos are never inspected, so asserting an observation
                // here would be exactly the invented-evidence failure the real prompt forbids.
                List.of(),
                new LikelyIssue(MOCK_PREFIX + "תקלה בתחום " + request.categoryNameHe()
                        + " — נדרשת בדיקה באתר.", 0.4, List.copyOf(evidence)),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    /**
     * Clarification answers are appended to the scoring text so a later pass can actually
     * move: answering "from the AC" adds AC keywords and breaks the tie the first pass hit.
     */
    private String scoringText(ClassificationRequest request) {
        StringBuilder text = new StringBuilder(request.description() == null ? "" : request.description());
        for (ClarificationExchange exchange : request.priorExchanges()) {
            text.append(' ').append(exchange.answer());
        }
        return text.toString();
    }

    /**
     * Weighted keyword hits per category, normalised into 0..1 confidences summing to 1 —
     * close scores therefore produce a small margin, which is exactly what the routing policy
     * reads as ambiguity. Categories missing from the live table are skipped, so a retired
     * category can never be scored.
     */
    private List<CategoryCandidate> score(String text, List<String> availableCodes) {
        Map<String, Double> hits = new LinkedHashMap<>();
        for (KeywordRule rule : RULES) {
            if (!availableCodes.contains(rule.categoryCode())) {
                continue;
            }
            long matches = rule.keywords().stream().filter(text::contains).count();
            if (matches > 0) {
                hits.put(rule.categoryCode(), matches * rule.weight());
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }

        double total = hits.values().stream().mapToDouble(Double::doubleValue).sum();
        List<CategoryCandidate> candidates = new ArrayList<>();
        hits.forEach((code, weightedHits) -> candidates.add(new CategoryCandidate(code, weightedHits / total)));
        candidates.sort(Comparator.comparingDouble(CategoryCandidate::confidence).reversed());
        return List.copyOf(candidates);
    }

    /** Order-independent key, so "plumbing vs ac_hvac" and "ac_hvac vs plumbing" find one question. */
    private static String pairKey(String first, String second) {
        return first.compareTo(second) <= 0 ? first + "|" + second : second + "|" + first;
    }

    private String truncate(String text) {
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }

    private record KeywordRule(String categoryCode, double weight, List<String> keywords) {
    }
}
