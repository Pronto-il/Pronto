package com.pronto.ai.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a run's outcomes into the numbers that decide whether the 95% target is met, and —
 * more usefully — where it is not.
 *
 * <p>Reports, per {@code docs/architecture} and the routing-redesign brief:
 * initial accuracy, <b>final accuracy</b> (the headline), clarification rate, average
 * questions asked, high-confidence wrong classifications, per-category accuracy and a
 * confusion matrix.
 *
 * <p>The metric worth watching alongside final accuracy is
 * {@link #highConfidenceWrong()}: a confident wrong answer sends the wrong trade to someone's
 * home without ever asking, which is a far worse product failure than an uncertain case that
 * correctly asked a question. High accuracy achieved with a high confident-wrong count is not
 * a system anyone should ship.
 *
 * <p>Pure computation over already-collected outcomes — no AI, no I/O — so its own arithmetic
 * is unit-testable ({@code EvaluationReportTest}) without a model call.
 */
public class EvaluationReport {

    private final List<EvaluationOutcome> outcomes;
    private final double highConfidenceThreshold;

    public EvaluationReport(List<EvaluationOutcome> outcomes, double highConfidenceThreshold) {
        this.outcomes = List.copyOf(outcomes);
        this.highConfidenceThreshold = highConfidenceThreshold;
    }

    public int total() {
        return outcomes.size();
    }

    /** Accuracy of the routing implied before any clarification question was asked. */
    public double initialAccuracy() {
        return ratio(outcomes.stream().filter(EvaluationOutcome::initiallyCorrect).count());
    }

    /** The headline number: accuracy after the complete clarification flow. */
    public double finalAccuracy() {
        return ratio(outcomes.stream().filter(EvaluationOutcome::finallyCorrect).count());
    }

    /**
     * Share of cases where routing gave up and used the {@code general_handyman} fallback
     * rather than committing to a specialist.
     *
     * <p><b>Read this next to {@link #finalAccuracy()}, always.</b> Diverting every hard case
     * to the fallback would push this up while leaving accuracy looking respectable, and that
     * is not a better routing system — it is the same uncertainty, relabelled. A rise here
     * without a matching rise in {@link #finalSpecificCategoryAccuracy()} means Pronto got
     * more cautious, not more correct.
     */
    public double unresolvedFallbackRate() {
        return ratio(outcomes.stream().filter(EvaluationOutcome::unresolved).count());
    }

    /**
     * Accuracy among only the cases where Pronto actually picked a category — fallbacks
     * excluded from both numerator and denominator.
     *
     * <p>Answers "when Pronto commits, how often is it right?", which is the question the
     * fallback rate cannot distort. {@code 0} when it never committed to anything.
     */
    public double finalSpecificCategoryAccuracy() {
        List<EvaluationOutcome> committed = outcomes.stream()
                .filter(EvaluationOutcome::committedToACategory)
                .toList();
        if (committed.isEmpty()) {
            return 0;
        }
        return (double) committed.stream().filter(EvaluationOutcome::finallyCorrect).count() / committed.size();
    }

    /**
     * Unresolved fallbacks whose expected answer happened to be {@code general_handyman}, so
     * they scored as correct in {@link #finalAccuracy()} without Pronto having decided
     * anything. Surfaced explicitly rather than left to be discovered — it is the one way the
     * headline number can flatter itself.
     */
    public List<EvaluationOutcome> luckyFallbacks() {
        return outcomes.stream()
                .filter(EvaluationOutcome::unresolved)
                .filter(EvaluationOutcome::finallyCorrect)
                .toList();
    }

    /** Share of cases that needed at least one question — the customer-friction cost. */
    public double clarificationRate() {
        return ratio(outcomes.stream().filter(EvaluationOutcome::askedClarification).count());
    }

    public double averageQuestions() {
        if (outcomes.isEmpty()) {
            return 0;
        }
        return outcomes.stream().mapToInt(EvaluationOutcome::questionsAsked).sum() / (double) outcomes.size();
    }

    /** Wrong final routing that the system was nonetheless confident about. */
    public List<EvaluationOutcome> highConfidenceWrong() {
        return outcomes.stream()
                .filter(outcome -> !outcome.finallyCorrect())
                .filter(outcome -> outcome.finalConfidence() != null
                        && outcome.finalConfidence() >= highConfidenceThreshold)
                .toList();
    }

    public List<EvaluationOutcome> failures() {
        return outcomes.stream().filter(EvaluationOutcome::failed).toList();
    }

    public List<EvaluationOutcome> unmatchedQuestions() {
        return outcomes.stream().filter(outcome -> outcome.unmatchedQuestion() != null).toList();
    }

    /** Final accuracy per expected category — where the taxonomy actually hurts. */
    public Map<String, Double> perCategoryAccuracy() {
        Map<String, int[]> tallies = new TreeMap<>();
        for (EvaluationOutcome outcome : outcomes) {
            int[] tally = tallies.computeIfAbsent(outcome.expectedCategory(), key -> new int[2]);
            tally[1]++;
            if (outcome.finallyCorrect()) {
                tally[0]++;
            }
        }
        Map<String, Double> accuracy = new LinkedHashMap<>();
        tallies.forEach((category, tally) -> accuracy.put(category, tally[1] == 0 ? 0 : (double) tally[0] / tally[1]));
        return accuracy;
    }

    /**
     * Mis-routings only, as {@code "expected -> predicted"} counts. The correct diagonal is
     * omitted on purpose: what improves routing rules is knowing which pairs get confused, and
     * burying that in a full matrix of mostly-correct cells makes it harder to see, not easier.
     */
    public Map<String, Integer> confusionMatrix() {
        Map<String, Integer> matrix = new TreeMap<>();
        for (EvaluationOutcome outcome : outcomes) {
            if (outcome.finallyCorrect()) {
                continue;
            }
            String predicted = outcome.finalCategory() == null ? "(none)" : outcome.finalCategory();
            matrix.merge(outcome.expectedCategory() + " -> " + predicted, 1, Integer::sum);
        }
        return matrix;
    }

    /** Human-readable summary, printed by the runners. */
    public String render() {
        StringBuilder report = new StringBuilder();
        report.append("=== Pronto routing evaluation ===\n");
        report.append(String.format("cases                          %d%n", total()));
        report.append(String.format("initial accuracy               %.1f%%%n", initialAccuracy() * 100));
        report.append(String.format("FINAL accuracy                 %.1f%%%n", finalAccuracy() * 100));
        report.append(String.format("final accuracy (committed only) %.1f%%  [n=%d]%n",
                finalSpecificCategoryAccuracy() * 100,
                outcomes.stream().filter(EvaluationOutcome::committedToACategory).count()));
        report.append(String.format("unresolved fallback rate       %.1f%%  [%d case(s)]%n",
                unresolvedFallbackRate() * 100, outcomes.stream().filter(EvaluationOutcome::unresolved).count()));
        report.append(String.format("  of which scored correct      %d   <- counted as correct above, "
                + "but nothing was decided%n", luckyFallbacks().size()));
        report.append(String.format("clarification rate             %.1f%%%n", clarificationRate() * 100));
        report.append(String.format("avg questions per case         %.2f%n", averageQuestions()));
        report.append(String.format("high-confidence wrong (>=%.2f)  %d%n",
                highConfidenceThreshold, highConfidenceWrong().size()));
        report.append(String.format("pipeline failures              %d%n", failures().size()));

        report.append("""

                -- how to read these --
                initial accuracy                before any clarification question; when the first pass
                                                asked instead of committing, its strongest candidate counts
                FINAL accuracy                  after the full clarification flow, INCLUDING unresolved
                                                fallbacks. The headline number.
                final accuracy (committed only) accuracy where Pronto actually picked a category. Fallbacks
                                                excluded from numerator and denominator — "when it commits,
                                                how often is it right?"
                unresolved fallback rate        cases routed to general_handyman because two materially
                                                different categories were still live. Rising here without a
                                                rising committed-only accuracy means more caution, not more
                                                correctness.
                high-confidence wrong           wrong AND confident: the worst failure mode, since nothing
                                                was asked before sending the wrong trade.
                """);

        report.append("\n-- per-category final accuracy --\n");
        perCategoryAccuracy().forEach((category, accuracy) ->
                report.append(String.format("%-22s %6.1f%%%n", category, accuracy * 100)));

        Map<String, Integer> matrix = confusionMatrix();
        report.append("\n-- confusion (expected -> predicted) --\n");
        if (matrix.isEmpty()) {
            report.append("(none)\n");
        } else {
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(matrix.entrySet());
            sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
            sorted.forEach(entry -> report.append(String.format("%-45s %d%n", entry.getKey(), entry.getValue())));
        }

        if (!highConfidenceWrong().isEmpty()) {
            report.append("\n-- high-confidence wrong (most dangerous) --\n");
            highConfidenceWrong().forEach(outcome -> report.append(String.format("%-12s expected=%-18s got=%-18s conf=%.2f%n",
                    outcome.caseId(), outcome.expectedCategory(), outcome.finalCategory(), outcome.finalConfidence())));
        }

        if (!failures().isEmpty()) {
            report.append("\n-- pipeline failures --\n");
            failures().forEach(outcome ->
                    report.append(String.format("%-12s %s%n", outcome.caseId(), outcome.failureReason())));
        }

        if (!unmatchedQuestions().isEmpty()) {
            report.append("\n-- questions with no scripted answer (dataset gaps, answered \"not sure\") --\n");
            unmatchedQuestions().forEach(outcome ->
                    report.append(String.format("%-12s %s%n", outcome.caseId(), outcome.unmatchedQuestion())));
        }

        return report.toString();
    }

    private double ratio(long count) {
        return outcomes.isEmpty() ? 0 : (double) count / outcomes.size();
    }
}
