package com.pronto.ai.eval;

import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.service.ClassificationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs labelled cases through the <b>real</b> classification pipeline — the same
 * {@code ClassificationService}, the same {@code RoutingDecisionPolicy}, the same thresholds
 * production uses. Only two things are simulated: the customer (scripted answers) and, for
 * now, images.
 *
 * <p>That matters for the 95% target: measuring the model in isolation would report a number
 * that no customer experiences. What is measured here is the routing <i>system</i>, including
 * its decision to ask rather than guess.
 *
 * <p>Not a Spring bean and not wired into any controller — this class only exists under
 * {@code src/test}, so there is no path by which a request can trigger a bulk evaluation run.
 */
public class ClassificationEvaluator {

    /** Chosen when no scripted answer matches — mirrors a real customer picking "not sure". */
    private static final List<String> NOT_SURE_MARKERS = List.of("לא בטוח", "לא יודע", "not sure", "unsure");

    private final ClassificationService classificationService;
    private final int maxRounds;

    /**
     * @param maxRounds hard stop on the loop. Set from
     *                  {@code pronto.ai.routing.max-clarification-questions}; the extra bound
     *                  here means a policy bug shows up as a failing evaluation rather than a
     *                  hung test.
     */
    public ClassificationEvaluator(ClassificationService classificationService, int maxRounds) {
        this.classificationService = classificationService;
        this.maxRounds = maxRounds;
    }

    public List<EvaluationOutcome> run(List<EvaluationCase> cases, Map<String, Long> categoryIdsByCode) {
        List<EvaluationOutcome> outcomes = new ArrayList<>();
        for (EvaluationCase testCase : cases) {
            outcomes.add(runOne(testCase, categoryIdsByCode));
        }
        return outcomes;
    }

    EvaluationOutcome runOne(EvaluationCase testCase, Map<String, Long> categoryIdsByCode) {
        Long selectedCategoryId = testCase.selectedCategory() == null
                ? null : categoryIdsByCode.get(testCase.selectedCategory());

        List<ClarificationExchange> answers = new ArrayList<>();
        List<ClarificationRound> rounds = new ArrayList<>();
        String initialCategory = null;
        String unmatchedQuestion = null;

        // Carried across iterations so each pending question can be closed out with the state
        // that followed its answer — that before/after pair is the whole usefulness signal.
        ClarificationQuestion pendingQuestion = null;
        String pendingAnswer = null;
        boolean pendingAnswerWasScripted = false;
        String pendingTopBefore = null;
        Double pendingConfidenceBefore = null;
        double pendingMarginBefore = 0;

        long startedAtNanos = System.nanoTime();

        try {
            for (int round = 0; round <= maxRounds; round++) {
                ClassificationSuggestion suggestion = classificationService.classify(
                        testCase.description(), testCase.imageKeys(), selectedCategoryId, answers);

                if (round == 0) {
                    initialCategory = bestGuess(suggestion);
                }

                if (pendingQuestion != null) {
                    rounds.add(new ClarificationRound(pendingQuestion.question(), pendingQuestion.options(),
                            pendingAnswer, pendingAnswerWasScripted, pendingTopBefore, bestGuess(suggestion),
                            pendingConfidenceBefore, suggestion.confidence(), pendingMarginBefore,
                            margin(suggestion)));
                    pendingQuestion = null;
                }

                // Both terminal states end the run. UNSUPPORTED_PROFESSION carries a null category
                // by construction, which is what finallyCorrect() checks for an unsupported case --
                // and what makes "forced into a supported category" detectable as a non-null one.
                if (suggestion.status() == ClassificationStatus.CLASSIFIED
                        || suggestion.status() == ClassificationStatus.UNSUPPORTED_PROFESSION) {
                    boolean unsupported = suggestion.status() == ClassificationStatus.UNSUPPORTED_PROFESSION;
                    return new EvaluationOutcome(testCase.id(), testCase.expectedCategory(), testCase.tier(),
                            initialCategory, suggestion.categoryCode(), suggestion.confidence(), answers.size(),
                            suggestion.lowConfidence(), suggestion.unresolved(), unsupported,
                            suggestion.detectedProfession(), unmatchedQuestion,
                            testCase.requiresClarification(), List.copyOf(rounds),
                            elapsedMillis(startedAtNanos), null);
                }

                ClarificationQuestion question = suggestion.questions().get(0);
                String answer = scriptedAnswer(testCase, question);
                boolean scripted = answer != null;
                if (!scripted) {
                    unmatchedQuestion = question.question();
                    answer = fallbackAnswer(question);
                }

                pendingQuestion = question;
                pendingAnswer = answer;
                pendingAnswerWasScripted = scripted;
                pendingTopBefore = bestGuess(suggestion);
                pendingConfidenceBefore = suggestion.confidence();
                pendingMarginBefore = margin(suggestion);

                answers.add(new ClarificationExchange(question.question(), answer));
            }

            // Reaching here means the pipeline kept asking past its own configured budget.
            // Recorded as a failure rather than quietly ignored — an unbounded clarification
            // loop is exactly the bug this harness should surface.
            return new EvaluationOutcome(testCase.id(), testCase.expectedCategory(), testCase.tier(),
                    initialCategory, null, null, answers.size(), false, false, false, null,
                    unmatchedQuestion, testCase.requiresClarification(), List.copyOf(rounds),
                    elapsedMillis(startedAtNanos),
                    "pipeline kept asking questions past the configured maximum of " + maxRounds);

        } catch (Exception e) {
            return new EvaluationOutcome(testCase.id(), testCase.expectedCategory(), testCase.tier(),
                    initialCategory, null, null, answers.size(), false, false, false, null,
                    unmatchedQuestion, testCase.requiresClarification(), List.copyOf(rounds),
                    elapsedMillis(startedAtNanos),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * Gap between the two strongest candidates — the quantity a good clarification question is
     * supposed to widen. Zero when the pass produced fewer than two candidates, which is
     * already an unambiguous state.
     */
    private double margin(ClassificationSuggestion suggestion) {
        List<CategoryCandidate> candidates = suggestion.candidates();
        if (candidates.size() < 2) {
            return 0;
        }
        return candidates.get(0).confidence() - candidates.get(1).confidence();
    }

    /**
     * The routing this pass implies: the committed category, or — when it asked instead — its
     * strongest candidate. See {@code EvaluationOutcome.initialCategory}.
     */
    private String bestGuess(ClassificationSuggestion suggestion) {
        if (suggestion.categoryCode() != null) {
            return suggestion.categoryCode();
        }
        return suggestion.candidates().stream()
                .findFirst()
                .map(CategoryCandidate::categoryCode)
                .orElse(null);
    }

    /**
     * Matches a generated question against the case's scripted answers by keyword, because the
     * exact wording is the model's choice and cannot be predicted. The first key that appears
     * in the question wins.
     */
    private String scriptedAnswer(EvaluationCase testCase, ClarificationQuestion question) {
        String normalized = question.question().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : testCase.clarificationAnswers().entrySet()) {
            if (normalized.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** "Not sure" when the question offers it, otherwise the last option. */
    private String fallbackAnswer(ClarificationQuestion question) {
        for (String option : question.options()) {
            String normalized = option.toLowerCase(Locale.ROOT);
            if (NOT_SURE_MARKERS.stream().anyMatch(normalized::contains)) {
                return option;
            }
        }
        return question.options().isEmpty() ? "אני לא בטוח/ה" : question.options().get(question.options().size() - 1);
    }
}
