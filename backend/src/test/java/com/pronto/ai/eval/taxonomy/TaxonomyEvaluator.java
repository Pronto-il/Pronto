package com.pronto.ai.eval.taxonomy;

import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.prompt.ClassificationPromptBuilder;
import com.pronto.ai.service.ClassificationService;
import com.pronto.ai.taxonomy.ProfessionTaxonomy;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs labelled cases through the <b>real</b> classification pipeline — the same
 * {@link ClassificationService}, the same {@code RoutingDecisionPolicy}, the same thresholds
 * production runs. Nothing about the classifier is stubbed.
 *
 * <p><b>One pass per case, and no scripted answers.</b> This differs deliberately from the older
 * {@code eval.ClassificationEvaluator}, which simulates a customer answering clarification
 * questions in order to measure end-to-end routing after a conversation. What is being measured
 * here is the classifier's first, unaided reading of what the customer wrote — because that is
 * what the dataset labels. Its {@code Needs Clarification} column says "this description is not
 * enough to route on", which is a claim about the first pass; feeding the model invented answers
 * would score a conversation the dataset never described.
 *
 * <p>So a {@code QUESTIONS} result is recorded as {@code needsClarification = true} and scored
 * against that column, and the profession the model was leaning towards is still recorded — a
 * classifier that asks about the right trade is doing better than one that asks about the wrong
 * one, and only keeping the label makes that visible.
 *
 * <p>Not a Spring bean, and reachable only from {@code src/test}: there is no path by which an
 * HTTP request can trigger a bulk evaluation run.
 */
public class TaxonomyEvaluator {

    private final ClassificationService classificationService;
    private final ProfessionTaxonomy taxonomy;
    private final String model;

    /**
     * Cost and latency for the call currently in flight, written by the
     * {@code OpenAiChatClient.UsageListener} the runner installs and read immediately afterwards.
     *
     * <p>A field rather than a return value because the listener fires deep inside the pipeline —
     * transport, parser, policy and service all sit between it and this class — and threading a
     * telemetry channel through four production types to serve a test would be the tail wagging
     * the dog. Safe because {@link #run} is strictly sequential: one call is in flight at a time,
     * and each is read out before the next begins.
     */
    private volatile long[] lastCall = NO_CALL;

    /** {@code {latencyMillis, attempts, promptTokens, completionTokens, reasoningTokens}}. */
    private static final long[] NO_CALL = {0, 0, 0, 0, 0};

    public TaxonomyEvaluator(ClassificationService classificationService, ProfessionTaxonomy taxonomy,
                              String model) {
        this.classificationService = classificationService;
        this.taxonomy = taxonomy;
        this.model = model;
    }

    /**
     * The listener to install on the {@code OpenAiChatClient} so this evaluator can attribute cost
     * and latency to each case.
     */
    public com.pronto.ai.client.OpenAiChatClient.UsageListener usageListener() {
        return (schemaName, latencyMillis, attempts, promptTokens, completionTokens, reasoningTokens,
                succeeded) ->
                lastCall = new long[]{latencyMillis, attempts, promptTokens, completionTokens,
                        reasoningTokens};
    }

    public List<TaxonomyEvaluationOutcome> run(List<TaxonomyEvaluationCase> cases) {
        List<TaxonomyEvaluationOutcome> outcomes = new ArrayList<>(cases.size());
        for (TaxonomyEvaluationCase testCase : cases) {
            outcomes.add(runOne(testCase));
        }
        return outcomes;
    }

    public TaxonomyEvaluationOutcome runOne(TaxonomyEvaluationCase testCase) {
        lastCall = NO_CALL;
        try {
            ClassificationSuggestion suggestion = classificationService.classify(
                    testCase.description(), List.of(), null, List.of());

            return outcome(testCase, suggestion, null);
        } catch (Exception e) {
            // Recorded, never rethrown: one provider hiccup 400 cases into a 3,500-case run must
            // not discard the 400 results already gathered. Errors are excluded from every accuracy
            // denominator and reported separately -- see TaxonomyEvaluationOutcome.error.
            return outcome(testCase, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private TaxonomyEvaluationOutcome outcome(TaxonomyEvaluationCase testCase,
                                               ClassificationSuggestion suggestion, String error) {
        boolean asked = suggestion != null && suggestion.status() == ClassificationStatus.QUESTIONS;

        return new TaxonomyEvaluationOutcome(
                testCase.id(),
                testCase.split(),
                testCase.description(),
                ClassificationPromptBuilder.PROMPT_VERSION,
                model,
                taxonomy.taxonomyVersion(),
                suggestion == null ? null : suggestion.status(),
                testCase.expectedProfession(),
                suggestion == null ? null : suggestion.professionCode(),
                testCase.expectedSubcategory(),
                suggestion == null ? null : suggestion.subcategoryCode(),
                testCase.expectedIntent(),
                suggestion == null || suggestion.intent() == null ? null : suggestion.intent().name(),
                testCase.expectedUrgency(),
                suggestion == null || suggestion.urgency() == null ? null : suggestion.urgency().name(),
                testCase.expectedNeedsClarification(),
                asked,
                suggestion == null ? null : suggestion.confidence(),
                testCase.expectedDispatchCategory(),
                suggestion == null ? null : suggestion.categoryCode(),
                suggestion != null && suggestion.isDispatchable(),
                0,
                testCase.descriptionStyle(),
                testCase.evalType(),
                error,
                // Never inferred. See FailureType -- this is filled in by a human reading the
                // failure list, and a guess here would become a statistic nobody checked.
                null,
                lastCall[0], (int) lastCall[1], (int) lastCall[2], (int) lastCall[3], (int) lastCall[4]);
    }
}
