package com.pronto.ai.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.within;

/**
 * The report's arithmetic, verified without any AI involvement — the numbers that will be
 * quoted about routing accuracy should themselves be tested, not trusted.
 */
class EvaluationReportTest {

    private static final double HIGH_CONFIDENCE = 0.85;

    private EvaluationOutcome outcome(String id, String expected, String initial, String finalCategory,
                                       Double confidence, int questions) {
        return new EvaluationOutcome(id, expected, EvaluationCase.TIER_CORE, initial, finalCategory, confidence,
                questions, false, false, null, false, List.of(), 0L, null);
    }

    /** An intentional fallback: routed to general_handyman because nothing could be separated. */
    private EvaluationOutcome fallback(String id, String expected) {
        return new EvaluationOutcome(id, expected, EvaluationCase.TIER_CORE, null, "general_handyman", null, 2,
                true, true, null, false, List.of(), 0L, null);
    }

    /**
     * @param topAfter    the strongest candidate once the answer was folded in
     * @param marginAfter gap between the top two candidates afterwards
     */
    private ClarificationRound round(String topBefore, String topAfter, double confidenceBefore,
                                      double confidenceAfter, double marginBefore, double marginAfter,
                                      List<String> options) {
        return new ClarificationRound("שאלה?", options, "תשובה", true, topBefore, topAfter,
                confidenceBefore, confidenceAfter, marginBefore, marginAfter);
    }

    private EvaluationOutcome withRounds(String id, String expected, String finalCategory,
                                          List<ClarificationRound> rounds) {
        return new EvaluationOutcome(id, expected, EvaluationCase.TIER_CORE, expected, finalCategory, 0.9,
                rounds.size(), false, false, null, false, rounds, 0L, null);
    }

    @Test
    void computesInitialAndFinalAccuracySeparately() {
        // Two cases start wrong; clarification rescues one of them.
        EvaluationReport report = new EvaluationReport(List.of(
                outcome("a", "plumbing", "plumbing", "plumbing", 0.9, 0),
                outcome("b", "ac_hvac", "electrical", "ac_hvac", 0.8, 1),
                outcome("c", "electrical", "ac_hvac", "ac_hvac", 0.6, 1),
                outcome("d", "locksmith", "locksmith", "locksmith", 0.95, 0)
        ), HIGH_CONFIDENCE);

        assertThat(report.total()).isEqualTo(4);
        assertThat(report.initialAccuracy()).isEqualTo(0.5);
        assertThat(report.finalAccuracy()).isEqualTo(0.75);
    }

    @Test
    void clarificationRateAndAverageQuestionsMeasureCustomerFriction() {
        EvaluationReport report = new EvaluationReport(List.of(
                outcome("a", "plumbing", "plumbing", "plumbing", 0.9, 0),
                outcome("b", "ac_hvac", "ac_hvac", "ac_hvac", 0.9, 2),
                outcome("c", "electrical", "electrical", "electrical", 0.9, 1),
                outcome("d", "painting", "painting", "painting", 0.9, 0)
        ), HIGH_CONFIDENCE);

        assertThat(report.clarificationRate()).isEqualTo(0.5);
        assertThat(report.averageQuestions()).isEqualTo(0.75);
    }

    @Test
    void highConfidenceWrongCountsOnlyConfidentMisroutes() {
        EvaluationReport report = new EvaluationReport(List.of(
                // Wrong and confident — the dangerous one.
                outcome("a", "ac_hvac", "electrical", "electrical", 0.93, 0),
                // Wrong but honestly uncertain — not counted.
                outcome("b", "plumbing", "ac_hvac", "ac_hvac", 0.40, 1),
                // Correct and confident — not counted.
                outcome("c", "locksmith", "locksmith", "locksmith", 0.99, 0),
                // Wrong, confidence unknown — not counted, cannot be judged.
                outcome("d", "painting", null, "plumbing", null, 0)
        ), HIGH_CONFIDENCE);

        assertThat(report.highConfidenceWrong()).extracting(EvaluationOutcome::caseId).containsExactly("a");
    }

    @Test
    void perCategoryAccuracyIsKeyedByExpectedCategory() {
        EvaluationReport report = new EvaluationReport(List.of(
                outcome("a", "plumbing", "plumbing", "plumbing", 0.9, 0),
                outcome("b", "plumbing", "plumbing", "ac_hvac", 0.5, 0),
                outcome("c", "ac_hvac", "ac_hvac", "ac_hvac", 0.9, 0)
        ), HIGH_CONFIDENCE);

        assertThat(report.perCategoryAccuracy())
                .containsExactly(entry("ac_hvac", 1.0), entry("plumbing", 0.5));
    }

    @Test
    void confusionMatrixListsOnlyMisroutesAndAggregatesRepeats() {
        EvaluationReport report = new EvaluationReport(List.of(
                outcome("a", "ac_hvac", "electrical", "electrical", 0.9, 0),
                outcome("b", "ac_hvac", "electrical", "electrical", 0.8, 0),
                outcome("c", "plumbing", "plumbing", "plumbing", 0.9, 0),
                new EvaluationOutcome("d", "locksmith", EvaluationCase.TIER_CORE, null, null, null, 0, false,
                        false, null, false, List.of(), 0L, "boom")
        ), HIGH_CONFIDENCE);

        assertThat(report.confusionMatrix())
                .containsExactly(entry("ac_hvac -> electrical", 2), entry("locksmith -> (none)", 1));
        assertThat(report.failures()).extracting(EvaluationOutcome::caseId).containsExactly("d");
    }

    @Test
    void unresolvedFallbacksAreCountedSeparatelyFromCommittedDecisions() {
        EvaluationReport report = new EvaluationReport(List.of(
                outcome("a", "plumbing", "plumbing", "plumbing", 0.9, 0),
                outcome("b", "ac_hvac", "ac_hvac", "ac_hvac", 0.9, 1),
                fallback("c", "electrical"),
                fallback("d", "locksmith")
        ), HIGH_CONFIDENCE);

        assertThat(report.unresolvedFallbackRate()).isEqualTo(0.5);
        // Headline accuracy counts the fallbacks as the misses they are...
        assertThat(report.finalAccuracy()).isEqualTo(0.5);
        // ...and among the cases Pronto actually decided, it was right every time.
        assertThat(report.finalSpecificCategoryAccuracy()).isEqualTo(1.0);
    }

    @Test
    void aFallbackThatHappensToMatchTheExpectedCategoryIsSurfacedNotHidden() {
        // The one way the headline number can flatter itself: the expected answer WAS handyman,
        // so the fallback scores as correct despite Pronto having decided nothing.
        EvaluationReport report = new EvaluationReport(List.of(
                outcome("a", "plumbing", "plumbing", "plumbing", 0.9, 0),
                fallback("b", "general_handyman")
        ), HIGH_CONFIDENCE);

        assertThat(report.finalAccuracy()).isEqualTo(1.0);
        assertThat(report.luckyFallbacks()).extracting(EvaluationOutcome::caseId).containsExactly("b");
        // Excluded from the committed-only view, which is the honest one here.
        assertThat(report.finalSpecificCategoryAccuracy()).isEqualTo(1.0);
        assertThat(report.render()).contains("of which scored correct");
    }

    @Test
    void committedOnlyAccuracyIsZeroWhenNothingWasEverCommitted() {
        EvaluationReport report = new EvaluationReport(List.of(
                fallback("a", "electrical"), fallback("b", "plumbing")), HIGH_CONFIDENCE);

        assertThat(report.finalSpecificCategoryAccuracy()).isZero();
        assertThat(report.unresolvedFallbackRate()).isEqualTo(1.0);
    }

    @Test
    void theRenderedReportExplainsWhatEachMetricMeans() {
        // The metrics are only useful if nobody has to guess whether fallbacks are included.
        String rendered = new EvaluationReport(List.of(
                outcome("a", "plumbing", "plumbing", "plumbing", 0.9, 0)), HIGH_CONFIDENCE).render();

        assertThat(rendered).contains("how to read these");
        assertThat(rendered).contains("FINAL accuracy");
        assertThat(rendered).contains("final accuracy (committed only)");
        assertThat(rendered).contains("unresolved fallback rate");
        assertThat(rendered).contains("high-confidence wrong");
    }

    @Test
    void questionDistributionShowsWhatTheAverageHides() {
        // Same 0.50 average, very different customer experiences — which is the point.
        EvaluationReport report = new EvaluationReport(List.of(
                outcome("a", "plumbing", "plumbing", "plumbing", 0.9, 0),
                outcome("b", "plumbing", "plumbing", "plumbing", 0.9, 0),
                outcome("c", "ac_hvac", "ac_hvac", "ac_hvac", 0.9, 0),
                outcome("d", "locksmith", "locksmith", "locksmith", 0.9, 2)
        ), HIGH_CONFIDENCE);

        assertThat(report.averageQuestions()).isEqualTo(0.5);
        assertThat(report.questionDistribution())
                .containsExactly(entry(0, 3L), entry(1, 0L), entry(2, 1L));
    }

    @Test
    void aQuestionCountsAsUsefulWhenItMovesRankingMarginOrConfidence() {
        ClarificationRound changedTop = round("plumbing", "ac_hvac", 0.5, 0.8, 0.05, 0.6, List.of("א", "ב"));
        ClarificationRound widenedMargin = round("plumbing", "plumbing", 0.5, 0.5, 0.05, 0.4, List.of("א", "ב"));
        ClarificationRound raisedConfidence = round("plumbing", "plumbing", 0.5, 0.9, 0.2, 0.2, List.of("א", "ב"));

        assertThat(changedTop.wasUseful()).isTrue();
        assertThat(changedTop.changedTopCandidate()).isTrue();
        assertThat(widenedMargin.wasUseful()).isTrue();
        assertThat(widenedMargin.increasedMargin()).isTrue();
        assertThat(raisedConfidence.wasUseful()).isTrue();
        assertThat(raisedConfidence.increasedConfidence()).isTrue();
    }

    @Test
    void aQuestionThatMovesNothingIsCountedAsUnnecessaryFriction() {
        // Identical state either side of the answer: the customer was interrupted for nothing.
        ClarificationRound inert = round("plumbing", "plumbing", 0.6, 0.6, 0.3, 0.3, List.of("א", "ב"));
        EvaluationReport report = new EvaluationReport(List.of(
                withRounds("a", "plumbing", "plumbing", List.of(inert)),
                outcome("b", "ac_hvac", "ac_hvac", "ac_hvac", 0.9, 0)
        ), HIGH_CONFIDENCE);

        assertThat(inert.wasUseful()).isFalse();
        assertThat(report.usefulClarificationRate()).isZero();
        assertThat(report.unnecessaryClarificationRate()).isEqualTo(0.5);
    }

    @Test
    void notSureOptionIsDetectedSoForcedGuessesAreVisible() {
        ClarificationRound withEscape = round("plumbing", "plumbing", 0.5, 0.9, 0.1, 0.5,
                List.of("כן", "לא", "לא בטוח"));
        ClarificationRound forced = round("plumbing", "plumbing", 0.5, 0.9, 0.1, 0.5, List.of("כן", "לא"));

        assertThat(withEscape.offeredNotSure()).isTrue();
        assertThat(forced.offeredNotSure()).isFalse();

        EvaluationReport report = new EvaluationReport(List.of(
                withRounds("a", "plumbing", "plumbing", List.of(withEscape)),
                withRounds("b", "plumbing", "plumbing", List.of(forced))
        ), HIGH_CONFIDENCE);
        assertThat(report.notSureOfferedRate()).isEqualTo(0.5);
    }

    @Test
    void aiCallsCountTheInitialPassPlusOnePerAnswer() {
        EvaluationReport report = new EvaluationReport(List.of(
                outcome("a", "plumbing", "plumbing", "plumbing", 0.9, 0),   // 1 call
                outcome("b", "ac_hvac", "ac_hvac", "ac_hvac", 0.9, 1),      // 2 calls
                outcome("c", "locksmith", "locksmith", "locksmith", 0.9, 2) // 3 calls
        ), HIGH_CONFIDENCE);

        assertThat(report.totalAiCalls()).isEqualTo(6);
    }

    @Test
    void questionQualityReviewShowsTheActualQuestionAndWhetherItHelped() {
        EvaluationReport report = new EvaluationReport(List.of(withRounds("a", "ac_hvac", "ac_hvac",
                List.of(round("plumbing", "ac_hvac", 0.4, 0.9, 0.02, 0.7, List.of("כן", "לא", "לא בטוח"))))),
                HIGH_CONFIDENCE);

        String review = report.renderQuestionQuality();
        assertThat(review).contains("שאלה?");
        assertThat(review).contains("כן | לא | לא בטוח");
        assertThat(review).contains("helped:  YES");
        assertThat(review).contains("changed top candidate");
    }

    /**
     * @param questions how many were actually asked
     */
    private EvaluationOutcome mustAsk(String id, String expected, String finalCategory, Double confidence,
                                       int questions) {
        return new EvaluationOutcome(id, expected, EvaluationCase.TIER_CHALLENGE, expected, finalCategory,
                confidence, questions, false, false, null, true, List.of(), 0L, null);
    }

    /**
     * The whole reason this metric exists: a guess that lands correctly is still a guess, and
     * accuracy alone scores it as a success. On a paired case the identical sentence has two
     * different right answers, so committing is unsound even when it happens to be right.
     */
    @Test
    void committingOnACaseThatCouldNotBeDecidedIsCountedEvenWhenTheGuessLands() {
        EvaluationReport report = new EvaluationReport(List.of(
                mustAsk("lucky", "locksmith", "locksmith", 0.9, 0),
                mustAsk("unlucky", "general_handyman", "locksmith", 0.9, 0),
                mustAsk("proper", "locksmith", "locksmith", 0.9, 1)
        ), HIGH_CONFIDENCE);

        assertThat(report.finalAccuracy()).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(report.committedWithoutAsking())
                .extracting(EvaluationOutcome::caseId)
                .containsExactly("lucky", "unlucky");
        assertThat(report.clarificationComplianceRate()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(report.render()).contains("COMMITTED WITHOUT ASKING");
    }

    @Test
    void casesMakingNoClarificationClaimAreExcludedFromCompliance() {
        // Most cases are answerable directly; demanding a question there would be its own defect.
        EvaluationReport report = new EvaluationReport(List.of(
                outcome("a", "plumbing", "plumbing", "plumbing", 0.9, 0),
                outcome("b", "ac_hvac", "ac_hvac", "ac_hvac", 0.9, 0)
        ), HIGH_CONFIDENCE);

        assertThat(report.committedWithoutAsking()).isEmpty();
        assertThat(report.clarificationComplianceRate()).isEqualTo(1.0);
        assertThat(report.render()).doesNotContain("COMMITTED WITHOUT ASKING");
    }

    @Test
    void emptyRunProducesZeroesRatherThanDivisionByZero() {
        EvaluationReport report = new EvaluationReport(List.of(), HIGH_CONFIDENCE);

        assertThat(report.total()).isZero();
        assertThat(report.finalAccuracy()).isZero();
        assertThat(report.averageQuestions()).isZero();
        assertThat(report.render()).contains("cases                          0");
    }
}
