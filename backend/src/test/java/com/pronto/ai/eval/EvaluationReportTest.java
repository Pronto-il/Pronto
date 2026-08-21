package com.pronto.ai.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * The report's arithmetic, verified without any AI involvement — the numbers that will be
 * quoted about routing accuracy should themselves be tested, not trusted.
 */
class EvaluationReportTest {

    private static final double HIGH_CONFIDENCE = 0.85;

    private EvaluationOutcome outcome(String id, String expected, String initial, String finalCategory,
                                       Double confidence, int questions) {
        return new EvaluationOutcome(id, expected, initial, finalCategory, confidence, questions, false, false,
                null, null);
    }

    /** An intentional fallback: routed to general_handyman because nothing could be separated. */
    private EvaluationOutcome fallback(String id, String expected) {
        return new EvaluationOutcome(id, expected, null, "general_handyman", null, 2, true, true, null, null);
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
                new EvaluationOutcome("d", "locksmith", null, null, null, 0, false, false, null, "boom")
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
    void emptyRunProducesZeroesRatherThanDivisionByZero() {
        EvaluationReport report = new EvaluationReport(List.of(), HIGH_CONFIDENCE);

        assertThat(report.total()).isZero();
        assertThat(report.finalAccuracy()).isZero();
        assertThat(report.averageQuestions()).isZero();
        assertThat(report.render()).contains("cases                          0");
    }
}
