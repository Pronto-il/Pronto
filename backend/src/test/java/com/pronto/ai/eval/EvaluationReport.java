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

    /**
     * How many cases needed 0, 1 or 2 questions — the distribution behind
     * {@link #averageQuestions()}, which a single mean hides. "0.4 questions on average" is
     * the same number whether 40% of customers answer one question or 20% answer two, and
     * those are different products.
     */
    public Map<Integer, Long> questionDistribution() {
        Map<Integer, Long> distribution = new TreeMap<>();
        int max = outcomes.stream().mapToInt(EvaluationOutcome::questionsAsked).max().orElse(0);
        for (int questions = 0; questions <= max; questions++) {
            int count = questions;
            distribution.put(questions,
                    outcomes.stream().filter(outcome -> outcome.questionsAsked() == count).count());
        }
        return distribution;
    }

    /** Every clarification exchange across every case, flattened. */
    public List<ClarificationRound> allRounds() {
        return outcomes.stream().flatMap(outcome -> outcome.rounds().stream()).toList();
    }

    /**
     * Share of clarification questions that moved something — ranking, margin or confidence.
     * The counterpart to {@link #clarificationRate()}: asking less is only an improvement if
     * what remains still does work.
     */
    public double usefulClarificationRate() {
        List<ClarificationRound> rounds = allRounds();
        if (rounds.isEmpty()) {
            return 0;
        }
        return (double) rounds.stream().filter(ClarificationRound::wasUseful).count() / rounds.size();
    }

    /**
     * Share of ALL cases that asked at least one question and got nothing out of any of them.
     * Denominated over every case, not just the ones that asked, so it reads directly as
     * "this fraction of customers was interrupted for no reason".
     */
    public double unnecessaryClarificationRate() {
        return ratio(outcomes.stream().filter(EvaluationOutcome::askedUselessly).count());
    }

    /** Share of questions that offered a "not sure" escape rather than forcing a guess (§11). */
    public double notSureOfferedRate() {
        List<ClarificationRound> rounds = allRounds();
        if (rounds.isEmpty()) {
            return 0;
        }
        return (double) rounds.stream().filter(ClarificationRound::offeredNotSure).count() / rounds.size();
    }

    /** Total model calls the run consumed — the OpenAI bill, in its native unit. */
    public int totalAiCalls() {
        return outcomes.stream().mapToInt(EvaluationOutcome::aiCalls).sum();
    }

    public double averageLatencyMillis() {
        if (outcomes.isEmpty()) {
            return 0;
        }
        return outcomes.stream().mapToLong(EvaluationOutcome::latencyMillis).sum() / (double) outcomes.size();
    }

    /** Slowest case first — where a timeout would bite. */
    public long maxLatencyMillis() {
        return outcomes.stream().mapToLong(EvaluationOutcome::latencyMillis).max().orElse(0);
    }

    /**
     * Cases that declared their description insufficient to separate two trades, and which
     * Pronto committed on anyway without asking.
     *
     * <p>The sharpest signal in the report for a boundary that is being guessed rather than
     * resolved — and one that {@link #finalAccuracy()} cannot show, because half of these
     * guesses land correctly and are scored as successes.
     */
    public List<EvaluationOutcome> committedWithoutAsking() {
        return outcomes.stream().filter(EvaluationOutcome::committedWithoutAsking).toList();
    }

    /** Of the cases that required a question, the share that actually got one. */
    public double clarificationComplianceRate() {
        List<EvaluationOutcome> required = outcomes.stream()
                .filter(EvaluationOutcome::expectedClarification)
                .toList();
        if (required.isEmpty()) {
            return 1;
        }
        return (double) required.stream().filter(outcome -> outcome.questionsAsked() > 0).count()
                / required.size();
    }

    /** Cases whose final routing was wrong, worst-first by confidence. */
    public List<EvaluationOutcome> incorrect() {
        return outcomes.stream()
                .filter(outcome -> !outcome.finallyCorrect())
                .sorted(Comparator.comparingDouble((EvaluationOutcome outcome) ->
                        outcome.finalConfidence() == null ? 0 : outcome.finalConfidence()).reversed())
                .toList();
    }

    /** Wrong final routing that the system was nonetheless confident about. */
    public List<EvaluationOutcome> highConfidenceWrong() {
        return outcomes.stream()
                .filter(outcome -> !outcome.finallyCorrect())
                .filter(outcome -> outcome.finalConfidence() != null
                        && outcome.finalConfidence() >= highConfidenceThreshold)
                .toList();
    }

    /** Cases whose ground truth is "Pronto does not cover this trade". */
    public List<EvaluationOutcome> unsupportedCases() {
        return outcomes.stream().filter(EvaluationOutcome::expectedUnsupported).toList();
    }

    /**
     * Of the cases that SHOULD end in the unsupported state, how many did.
     *
     * <p>Reported separately from headline accuracy rather than folded into it, and the reason is
     * the same one the unresolved-fallback rate exists for: these two populations can move in
     * opposite directions. A model that got cautious and returned no category for everything would
     * score 100% here while destroying supported routing, and a model that forced every trade into
     * a category would score 0% here while leaving headline accuracy untouched. One number cannot
     * show both.
     */
    public double unsupportedAccuracy() {
        List<EvaluationOutcome> unsupported = unsupportedCases();
        if (unsupported.isEmpty()) {
            return Double.NaN;
        }
        return (double) unsupported.stream().filter(EvaluationOutcome::finallyCorrect).count()
                / unsupported.size();
    }

    /**
     * The worst failure mode in this report: a trade Pronto does not offer, routed to a Pronto
     * category anyway.
     *
     * <p>Listed by case rather than summarised, because each one is a professional dispatched to a
     * job they cannot do — the specific category it was forced into is exactly what a reader needs
     * in order to fix the prompt boundary that allowed it.
     */
    public List<EvaluationOutcome> forcedIntoSupportedCategory() {
        return outcomes.stream().filter(EvaluationOutcome::forcedIntoSupportedCategory).toList();
    }

    /**
     * The mirror image: a trade Pronto DOES cover, reported as unsupported. A customer turned away
     * from a job Pronto could have done — quieter than the previous failure, and just as wrong.
     */
    public List<EvaluationOutcome> wronglyReportedUnsupported() {
        return outcomes.stream()
                .filter(outcome -> !outcome.expectedUnsupported() && outcome.unsupportedProfession())
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
        report.append(String.format("useful clarification rate      %.1f%%  [of %d question(s) asked]%n",
                usefulClarificationRate() * 100, allRounds().size()));
        report.append(String.format("unnecessary clarification rate %.1f%%  [cases interrupted for nothing]%n",
                unnecessaryClarificationRate() * 100));
        report.append(String.format("\"not sure\" offered             %.1f%% of questions%n",
                notSureOfferedRate() * 100));
        report.append(String.format("high-confidence wrong (>=%.2f)  %d%n",
                highConfidenceThreshold, highConfidenceWrong().size()));
        if (!unsupportedCases().isEmpty()) {
            report.append(String.format("unsupported-profession accuracy %.1f%%  [n=%d]%n",
                    unsupportedAccuracy() * 100, unsupportedCases().size()));
            report.append(String.format("  forced into a Pronto category %d   <- a professional sent to a "
                    + "job they cannot do%n", forcedIntoSupportedCategory().size()));
            report.append(String.format("  supported, wrongly refused    %d   <- a customer turned away from "
                    + "a job Pronto covers%n", wronglyReportedUnsupported().size()));
        }
        report.append(String.format("pipeline failures              %d%n", failures().size()));
        report.append(String.format("total AI calls                 %d  (%.2f per case)%n",
                totalAiCalls(), outcomes.isEmpty() ? 0 : totalAiCalls() / (double) outcomes.size()));
        report.append(String.format("latency avg / max              %.0f ms / %d ms%n",
                averageLatencyMillis(), maxLatencyMillis()));

        report.append("\n-- questions asked per case --\n");
        questionDistribution().forEach((questions, count) -> report.append(String.format(
                "%d question(s)%-12s %3d case(s)  %5.1f%%%n", questions, "", count,
                outcomes.isEmpty() ? 0 : count * 100.0 / outcomes.size())));

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

        long requiringClarification = outcomes.stream()
                .filter(EvaluationOutcome::expectedClarification).count();
        if (requiringClarification > 0) {
            report.append(String.format("%n-- cases whose description cannot separate the trades --%n"));
            report.append(String.format("asked as required        %.1f%%  [%d of %d]%n",
                    clarificationComplianceRate() * 100,
                    requiringClarification - committedWithoutAsking().size(), requiringClarification));
            if (!committedWithoutAsking().isEmpty()) {
                report.append("COMMITTED WITHOUT ASKING (a guess, whether or not it landed):\n");
                committedWithoutAsking().forEach(outcome -> report.append(String.format(
                        "  %-12s expected=%-18s got=%-18s conf=%-6s %s%n",
                        outcome.caseId(), outcome.expectedCategory(),
                        outcome.finalCategory() == null ? "(none)" : outcome.finalCategory(),
                        outcome.finalConfidence() == null ? "n/a"
                                : String.format("%.2f", outcome.finalConfidence()),
                        outcome.finallyCorrect() ? "[landed correctly - still a guess]" : "[WRONG]")));
            }
        }

        if (!incorrect().isEmpty()) {
            report.append("\n-- every incorrect case (confident ones first) --\n");
            incorrect().forEach(outcome -> report.append(String.format(
                    "%-12s [%s] expected=%-18s got=%-18s conf=%-6s questions=%d%s%n",
                    outcome.caseId(), outcome.tier(), outcome.expectedCategory(),
                    outcome.finalCategory() == null ? "(none)" : outcome.finalCategory(),
                    outcome.finalConfidence() == null ? "n/a" : String.format("%.2f", outcome.finalConfidence()),
                    outcome.questionsAsked(), outcome.unresolved() ? "  [unresolved fallback]" : "")));
        }

        if (!unmatchedQuestions().isEmpty()) {
            report.append("\n-- questions with no scripted answer (dataset gaps, answered \"not sure\") --\n");
            unmatchedQuestions().forEach(outcome ->
                    report.append(String.format("%-12s %s%n", outcome.caseId(), outcome.unmatchedQuestion())));
        }

        return report.toString();
    }

    /**
     * The human-readable question-quality review (roadmap §33). Aggregate usefulness rates say
     * how often questions helped; only reading the actual questions says whether they are the
     * kind of question a customer can answer — discriminative, closed, and about an observable
     * symptom rather than a diagnosis.
     *
     * <p>Separate from {@link #render()} because it is long and is read deliberately, not
     * skimmed alongside the headline metrics.
     */
    public String renderQuestionQuality() {
        StringBuilder review = new StringBuilder("=== clarification question review ===\n");
        List<EvaluationOutcome> asked = outcomes.stream()
                .filter(EvaluationOutcome::askedClarification)
                .toList();

        if (asked.isEmpty()) {
            return review.append("(no clarification questions were asked in this run)\n").toString();
        }

        for (EvaluationOutcome outcome : asked) {
            review.append(String.format("%n%s [%s]  expected=%s  final=%s%s%n",
                    outcome.caseId(), outcome.tier(), outcome.expectedCategory(),
                    outcome.finalCategory() == null ? "(none)" : outcome.finalCategory(),
                    outcome.finallyCorrect() ? "  CORRECT" : "  WRONG"));

            int index = 1;
            for (ClarificationRound round : outcome.rounds()) {
                review.append(String.format("  Q%d: %s%n", index++, round.question()));
                review.append(String.format("      options: %s%s%n", String.join(" | ", round.options()),
                        round.offeredNotSure() ? "" : "   <- no \"not sure\" option"));
                review.append(String.format("      answer:  %s%s%n", round.answer(),
                        round.answerWasScripted() ? "" : "   <- NOT SCRIPTED (dataset gap)"));
                review.append(String.format("      top:     %s (%.2f)  ->  %s (%.2f)%n",
                        round.topBefore(), round.confidenceBefore() == null ? 0 : round.confidenceBefore(),
                        round.topAfter(), round.confidenceAfter() == null ? 0 : round.confidenceAfter()));
                review.append(String.format("      margin:  %.2f -> %.2f%n",
                        round.marginBefore(), round.marginAfter()));
                review.append(String.format("      helped:  %s%s%s%s%n",
                        round.wasUseful() ? "YES" : "NO",
                        round.changedTopCandidate() ? "  (changed top candidate)" : "",
                        round.increasedMargin() ? "  (widened margin)" : "",
                        round.increasedConfidence() ? "  (raised confidence)" : ""));
            }
        }
        return review.toString();
    }

    private double ratio(long count) {
        return outcomes.isEmpty() ? 0 : (double) count / outcomes.size();
    }
}
