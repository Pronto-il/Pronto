package com.pronto.ai.eval.taxonomy;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Turns a run's outcomes into the numbers a prompt revision is actually judged on, plus the
 * failure detail needed to work out why.
 *
 * <p>Two rules govern every figure here:
 *
 * <ol>
 *   <li><b>Errors are never counted as wrong answers.</b> They are excluded from every accuracy
 *       denominator and reported on their own line. A provider outage that halved a run would
 *       otherwise look exactly like a prompt regression, and the two need completely different
 *       responses.</li>
 *   <li><b>Classification accuracy never depends on dispatch.</b> A correct profession Pronto
 *       does not sell counts as correct. Dispatch gets its own section, so shrinking the taxonomy
 *       to what Pronto already sells can never be a way to make the headline number go up.</li>
 * </ol>
 */
public class TaxonomyEvaluationReport {

    private final List<TaxonomyEvaluationOutcome> outcomes;
    private final List<TaxonomyEvaluationOutcome> judged;
    private final double highConfidenceThreshold;

    public TaxonomyEvaluationReport(List<TaxonomyEvaluationOutcome> outcomes, double highConfidenceThreshold) {
        this.outcomes = List.copyOf(outcomes);
        this.judged = outcomes.stream().filter(outcome -> !outcome.isError()).toList();
        this.highConfidenceThreshold = highConfidenceThreshold;
    }

    public List<TaxonomyEvaluationOutcome> errors() {
        return outcomes.stream().filter(TaxonomyEvaluationOutcome::isError).toList();
    }

    public List<TaxonomyEvaluationOutcome> failures() {
        return judged.stream().filter(outcome -> !outcome.professionCorrect()).toList();
    }

    public double professionAccuracy() {
        return rate(TaxonomyEvaluationOutcome::professionCorrect);
    }

    public double subcategoryAccuracy() {
        return rate(TaxonomyEvaluationOutcome::subcategoryCorrect);
    }

    public double intentAccuracy() {
        return rate(TaxonomyEvaluationOutcome::intentCorrect);
    }

    public double urgencyAccuracy() {
        return rate(TaxonomyEvaluationOutcome::urgencyCorrect);
    }

    public double clarificationAccuracy() {
        return rate(TaxonomyEvaluationOutcome::clarificationCorrect);
    }

    public double dispatchAccuracy() {
        return rate(TaxonomyEvaluationOutcome::dispatchCorrect);
    }

    /**
     * How often a trade Pronto does not dispatch was nevertheless routed into some category.
     *
     * <p><b>The number this architecture exists to keep at zero.</b> Every one of these is a
     * customer sent a professional who cannot do the job.
     */
    public long forcedIntoDispatch() {
        return judged.stream().filter(TaxonomyEvaluationOutcome::forcedIntoDispatch).count();
    }

    public long confidentlyWrong() {
        return judged.stream()
                .filter(outcome -> outcome.confidentlyWrong(highConfidenceThreshold))
                .count();
    }

    private double rate(Predicate<TaxonomyEvaluationOutcome> correct) {
        if (judged.isEmpty()) {
            return 0;
        }
        return (double) judged.stream().filter(correct).count() / judged.size();
    }

    /**
     * Accuracy for the unsupported-profession path on its own.
     *
     * <p>Reported separately because it is the requirement the two-layer split exists to satisfy,
     * and because it is invisible in the headline: undispatchable trades are only a third of the
     * label space, so this could collapse entirely while profession accuracy barely moved.
     * "Correct" here means the profession was identified <em>and</em> nothing was dispatched.
     */
    public double unsupportedHandlingAccuracy() {
        List<TaxonomyEvaluationOutcome> unsupported = judged.stream()
                .filter(outcome -> outcome.expectedDispatchCategory() == null)
                .toList();
        if (unsupported.isEmpty()) {
            return 0;
        }
        return (double) unsupported.stream()
                .filter(outcome -> outcome.professionCorrect() && outcome.dispatchCategory() == null)
                .count() / unsupported.size();
    }

    public long unsupportedCaseCount() {
        return judged.stream().filter(outcome -> outcome.expectedDispatchCategory() == null).count();
    }

    /** Correct trade, wrong dispatch behaviour — the classification layer worked and routing did not. */
    public List<TaxonomyEvaluationOutcome> correctClassificationWrongDispatch() {
        return judged.stream()
                .filter(outcome -> outcome.professionCorrect() && !outcome.dispatchCorrect())
                .toList();
    }

    /** Asked a question the dataset says was unnecessary. Friction with no information gained. */
    public List<TaxonomyEvaluationOutcome> askedUnnecessarily() {
        return judged.stream()
                .filter(outcome -> outcome.needsClarification() && !outcome.expectedNeedsClarification())
                .toList();
    }

    /** Committed where the dataset says the description could not settle the trade. */
    public List<TaxonomyEvaluationOutcome> shouldHaveAskedButDidNot() {
        return judged.stream()
                .filter(outcome -> !outcome.needsClarification() && outcome.expectedNeedsClarification())
                .toList();
    }

    public long totalPromptTokens() {
        return outcomes.stream().mapToLong(TaxonomyEvaluationOutcome::promptTokens).sum();
    }

    public long totalCompletionTokens() {
        return outcomes.stream().mapToLong(TaxonomyEvaluationOutcome::completionTokens).sum();
    }

    public long totalReasoningTokens() {
        return outcomes.stream().mapToLong(TaxonomyEvaluationOutcome::reasoningTokens).sum();
    }

    /** Extra HTTP attempts beyond the first — how often the retry policy actually fired. */
    public long retryCount() {
        return outcomes.stream()
                .mapToLong(outcome -> Math.max(0, outcome.attempts() - 1))
                .sum();
    }

    public long latencyPercentile(int percentile) {
        List<Long> sorted = outcomes.stream()
                .map(TaxonomyEvaluationOutcome::latencyMillis)
                .filter(millis -> millis > 0)
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    public double meanLatencyMillis() {
        return outcomes.stream()
                .mapToLong(TaxonomyEvaluationOutcome::latencyMillis)
                .filter(millis -> millis > 0)
                .average().orElse(0);
    }

    /**
     * Per-profession accuracy, worst first.
     *
     * <p>The headline average hides exactly what needs fixing: 50 professions at a mean of 85%
     * could be 50 professions at 85% or 42 at 100% and 8 at 5%, and those call for completely
     * different work.
     */
    public String renderByProfession(int limit) {
        Map<String, List<TaxonomyEvaluationOutcome>> byProfession = judged.stream()
                .collect(Collectors.groupingBy(TaxonomyEvaluationOutcome::expectedProfession,
                        java.util.TreeMap::new, Collectors.toList()));

        record Row(String profession, int n, double profession_, double subcategory) { }
        List<Row> rows = byProfession.entrySet().stream()
                .map(entry -> {
                    List<TaxonomyEvaluationOutcome> group = entry.getValue();
                    double prof = (double) group.stream()
                            .filter(TaxonomyEvaluationOutcome::professionCorrect).count() / group.size();
                    double sub = (double) group.stream()
                            .filter(TaxonomyEvaluationOutcome::subcategoryCorrect).count() / group.size();
                    return new Row(entry.getKey(), group.size(), prof, sub);
                })
                .sorted(Comparator.comparingDouble(Row::profession_)
                        .thenComparing(Row::profession))
                .toList();

        StringBuilder out = new StringBuilder();
        out.append(String.format("%-34s %4s %10s %12s%n", "profession", "n", "profession", "subcategory"));
        rows.stream().limit(limit <= 0 ? rows.size() : limit).forEach(row ->
                out.append(String.format("%-34s %4d %9.1f%% %11.1f%%%n",
                        row.profession(), row.n(), row.profession_() * 100, row.subcategory() * 100)));
        return out.toString();
    }

    /**
     * Estimated spend. <b>The token counts above are measured; this is arithmetic on a rate that
     * is not.</b> Pricing is not discoverable from the API response, so it is a constant here and
     * must be checked against current published pricing before being quoted as a cost.
     */
    public String renderCost(double inputPerMillion, double outputPerMillion) {
        double input = totalPromptTokens() / 1_000_000.0 * inputPerMillion;
        double output = totalCompletionTokens() / 1_000_000.0 * outputPerMillion;
        return String.format("input  %,d tok x $%.2f/M = $%.4f%n"
                        + "output %,d tok x $%.2f/M = $%.4f   (of which %,d reasoning)%n"
                        + "total  %,d tok                = $%.4f%n",
                totalPromptTokens(), inputPerMillion, input,
                totalCompletionTokens(), outputPerMillion, output, totalReasoningTokens(),
                totalPromptTokens() + totalCompletionTokens(), input + output);
    }

    /** One tab-separated line per case, for the diagnostic lists. */
    public static String renderCaseLines(List<TaxonomyEvaluationOutcome> cases, int limit) {
        StringBuilder out = new StringBuilder(
                "id\texpProf\tpredProf\texpSub\tpredSub\texpIntent\tpredIntent\texpUrg\tpredUrg"
                        + "\texpClar\tpredClar\tconf\tdispatch\tdescription\n");
        cases.stream().limit(limit).forEach(o -> out
                .append(o.id()).append('\t')
                .append(o.expectedProfession()).append('\t').append(o.predictedProfession()).append('\t')
                .append(o.expectedSubcategory()).append('\t').append(o.predictedSubcategory()).append('\t')
                .append(o.expectedIntent()).append('\t').append(o.predictedIntent()).append('\t')
                .append(o.expectedUrgency()).append('\t').append(o.predictedUrgency()).append('\t')
                .append(o.expectedNeedsClarification()).append('\t').append(o.needsClarification()).append('\t')
                .append(o.confidence() == null ? "-" : String.format("%.2f", o.confidence())).append('\t')
                .append(o.expectedDispatchCategory()).append("->")
                .append(o.dispatchCategory() == null ? "none" : o.dispatchCategory()).append('\t')
                .append(o.description() == null ? "" : o.description().replace('\t', ' ')).append('\n'));
        if (cases.size() > limit) {
            out.append("... ").append(cases.size() - limit).append(" more not shown\n");
        }
        return out.toString();
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        TaxonomyEvaluationOutcome first = outcomes.isEmpty() ? null : outcomes.get(0);

        out.append("cases            ").append(outcomes.size())
                .append("  (judged ").append(judged.size())
                .append(", errored ").append(errors().size()).append(")\n");
        if (first != null) {
            out.append("prompt/model     ").append(first.promptVersion()).append("  ")
                    .append(first.model()).append("\n");
            out.append("taxonomy         ").append(first.taxonomyVersion()).append("\n");
        }
        out.append('\n');

        out.append("--- CLASSIFICATION (independent of whether Pronto dispatches the answer) ---\n");
        out.append(percent("profession      ", professionAccuracy()));
        out.append(percent("subcategory     ", subcategoryAccuracy()));
        out.append(percent("intent          ", intentAccuracy()));
        out.append(percent("urgency         ", urgencyAccuracy()));
        out.append(percent("clarification   ", clarificationAccuracy()));
        out.append('\n');

        out.append("--- DISPATCH (scored only where the profession was right) ---\n");
        out.append(percent("dispatch        ", dispatchAccuracy()));
        out.append(String.format("unsupported     %5.1f%%   (n=%d undispatchable trades: identified "
                        + "AND not dispatched)%n",
                unsupportedHandlingAccuracy() * 100, unsupportedCaseCount()));
        out.append("forcedIntoDispatch ").append(forcedIntoDispatch())
                .append("   <- must be 0; every one is a wasted visit\n");
        out.append("confidentlyWrong   ").append(confidentlyWrong())
                .append("   (wrong profession, no question asked, confidence >= ")
                .append(highConfidenceThreshold).append(")\n");

        out.append('\n');
        out.append("--- CLARIFICATION BEHAVIOUR ---\n");
        out.append("askedUnnecessarily      ").append(askedUnnecessarily().size()).append('\n');
        out.append("shouldHaveAskedDidNot   ").append(shouldHaveAskedButDidNot().size()).append('\n');
        out.append("correctClassWrongDispatch ").append(correctClassificationWrongDispatch().size())
                .append('\n');

        out.append('\n');
        out.append("--- COST AND LATENCY ---\n");
        out.append(String.format("latency  mean %,.0f ms   p50 %,d ms   p95 %,d ms   max %,d ms%n",
                meanLatencyMillis(), latencyPercentile(50), latencyPercentile(95),
                latencyPercentile(100)));
        out.append("retries  ").append(retryCount())
                .append("   (extra HTTP attempts beyond the first)\n");

        out.append('\n').append(renderConfusion());
        out.append('\n').append(renderByEvalType());
        return out.toString();
    }

    /**
     * The profession pairs that actually get confused, most frequent first.
     *
     * <p>This is the section prompt work starts from: a boundary rule can be written for
     * "LEAK_DETECTION mistaken for PLUMBER, 31 times", and cannot be written for "84% accuracy".
     * Capped at the top 25 because a long tail of one-off confusions is noise, and the cap is
     * printed so a truncated list never reads as a complete one.
     */
    public String renderConfusion() {
        Map<String, Long> pairs = failures().stream().collect(Collectors.groupingBy(
                outcome -> outcome.expectedProfession() + "  ->  "
                        + (outcome.predictedProfession() == null ? "(none)" : outcome.predictedProfession()),
                LinkedHashMap::new, Collectors.counting()));

        if (pairs.isEmpty()) {
            return "--- CONFUSION ---\n(no profession failures)\n";
        }

        List<Map.Entry<String, Long>> ranked = pairs.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList();

        StringBuilder out = new StringBuilder("--- CONFUSION (expected -> predicted) ---\n");
        ranked.stream().limit(CONFUSION_LIMIT).forEach(entry ->
                out.append(String.format("%5d  %s%n", entry.getValue(), entry.getKey())));
        if (ranked.size() > CONFUSION_LIMIT) {
            out.append("  ... ").append(ranked.size() - CONFUSION_LIMIT)
                    .append(" further pair(s) not shown\n");
        }
        return out.toString();
    }

    private static final int CONFUSION_LIMIT = 25;

    /**
     * Accuracy split by the workbook's own {@code Eval Type}.
     *
     * <p>Worth its own section because the three are not the same task: {@code AMBIGUOUS} rows are
     * labelled as needing a question, so a high profession accuracy on them is not obviously good
     * news — it may mean the classifier committed where it should have asked.
     */
    public String renderByEvalType() {
        Map<String, List<TaxonomyEvaluationOutcome>> byType = judged.stream()
                .collect(Collectors.groupingBy(
                        outcome -> outcome.evalType() == null ? "(none)" : outcome.evalType(),
                        LinkedHashMap::new, Collectors.toList()));

        StringBuilder out = new StringBuilder("--- BY EVAL TYPE ---\n");
        byType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<TaxonomyEvaluationOutcome> group = entry.getValue();
                    long professionOk = group.stream()
                            .filter(TaxonomyEvaluationOutcome::professionCorrect).count();
                    long clarificationOk = group.stream()
                            .filter(TaxonomyEvaluationOutcome::clarificationCorrect).count();
                    out.append(String.format("%-12s n=%-5d profession=%5.1f%%  clarification=%5.1f%%%n",
                            entry.getKey(), group.size(),
                            100.0 * professionOk / group.size(),
                            100.0 * clarificationOk / group.size()));
                });
        return out.toString();
    }

    /**
     * Every failed case, one line each, ready to be pasted into a review document and annotated
     * with a {@link FailureType}.
     *
     * <p>Carries the dataset ID first so any line can be taken straight back to the workbook row,
     * and the customer's own words last so the failure can be judged without opening anything.
     */
    public String renderFailureList(int limit) {
        List<TaxonomyEvaluationOutcome> failures = failures().stream()
                .sorted(Comparator.comparingInt(TaxonomyEvaluationOutcome::id))
                .limit(limit)
                .toList();

        StringBuilder out = new StringBuilder("--- FAILURES (annotate each with a FailureType) ---\n");
        out.append("id\tsplit\texpected\tpredicted\tconf\tasked\tdescription\n");
        for (TaxonomyEvaluationOutcome outcome : failures) {
            out.append(outcome.id()).append('\t')
                    .append(outcome.split()).append('\t')
                    .append(outcome.expectedProfession()).append('/')
                    .append(outcome.expectedSubcategory()).append('\t')
                    .append(outcome.predictedProfession()).append('/')
                    .append(outcome.predictedSubcategory()).append('\t')
                    .append(outcome.confidence() == null ? "-"
                            : String.format("%.2f", outcome.confidence())).append('\t')
                    .append(outcome.needsClarification() ? "asked" : "-").append('\t')
                    .append(outcome.description()).append('\n');
        }
        if (failures().size() > limit) {
            out.append("... ").append(failures().size() - limit).append(" further failure(s) not shown\n");
        }
        return out.toString();
    }

    /**
     * The whole run as TSV, one row per case — the artefact error analysis actually works from.
     *
     * <p>TSV rather than CSV because the descriptions are free Hebrew text containing commas and
     * quotes far more often than tabs, and quote-escaping a 5,000-row file by hand is how a
     * spreadsheet silently loses rows.
     */
    public String renderTsv() {
        StringBuilder out = new StringBuilder();
        out.append(String.join("\t",
                "id", "split", "promptVersion", "model", "taxonomyVersion", "status",
                "expectedProfession", "predictedProfession", "expectedSubcategory", "predictedSubcategory",
                "expectedIntent", "predictedIntent", "expectedUrgency", "predictedUrgency",
                "expectedNeedsClarification", "predictedNeedsClarification", "confidence",
                "expectedDispatchCategory", "dispatchCategory", "dispatchable",
                "professionCorrect", "subcategoryCorrect", "intentCorrect", "urgencyCorrect",
                "clarificationCorrect", "dispatchCorrect", "descriptionStyle", "evalType",
                "error", "failureType", "description")).append('\n');

        for (TaxonomyEvaluationOutcome o : outcomes) {
            out.append(String.join("\t",
                    String.valueOf(o.id()), nullSafe(o.split()), nullSafe(o.promptVersion()),
                    nullSafe(o.model()), nullSafe(o.taxonomyVersion()),
                    o.status() == null ? "" : o.status().name(),
                    nullSafe(o.expectedProfession()), nullSafe(o.predictedProfession()),
                    nullSafe(o.expectedSubcategory()), nullSafe(o.predictedSubcategory()),
                    nullSafe(o.expectedIntent()), nullSafe(o.predictedIntent()),
                    nullSafe(o.expectedUrgency()), nullSafe(o.predictedUrgency()),
                    String.valueOf(o.expectedNeedsClarification()), String.valueOf(o.needsClarification()),
                    o.confidence() == null ? "" : String.format("%.3f", o.confidence()),
                    nullSafe(o.expectedDispatchCategory()), nullSafe(o.dispatchCategory()),
                    String.valueOf(o.dispatchable()),
                    String.valueOf(o.professionCorrect()), String.valueOf(o.subcategoryCorrect()),
                    String.valueOf(o.intentCorrect()), String.valueOf(o.urgencyCorrect()),
                    String.valueOf(o.clarificationCorrect()), String.valueOf(o.dispatchCorrect()),
                    nullSafe(o.descriptionStyle()), nullSafe(o.evalType()),
                    nullSafe(o.error()), o.failureType() == null ? "" : o.failureType().name(),
                    tabSafe(o.description()))).append('\n');
        }
        return out.toString();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** A stray tab or newline in customer text would shift every later column by one. */
    private static String tabSafe(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String percent(String label, double rate) {
        return String.format("%s %5.1f%%%n", label, rate * 100);
    }
}
