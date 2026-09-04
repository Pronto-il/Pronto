package com.pronto.ai.eval.taxonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.TestCategories;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.client.OpenAiChatClient;
import com.pronto.ai.client.OpenAiClassificationClient;
import com.pronto.ai.decision.RoutingDecisionPolicy;
import com.pronto.ai.decision.RoutingProperties;
import com.pronto.ai.prompt.ClassificationPromptBuilder;
import com.pronto.ai.prompt.ClassificationSchema;
import com.pronto.ai.prompt.ProfessionalBriefPromptBuilder;
import com.pronto.ai.prompt.ProfessionalBriefSchema;
import com.pronto.ai.service.ClassificationService;
import com.pronto.ai.service.IssueImageResolver;
import com.pronto.ai.taxonomy.ProfessionTaxonomy;
import com.pronto.storage.client.StorageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evaluation harness: runs the labelled 5,000-row dataset against live OpenAI and reports
 * classification accuracy, dispatch accuracy and the confusion pairs.
 *
 * <pre>
 *   export PATH="$PATH:/c/Users/orcoh/.local-tools/apache-maven-3.9.11/bin"
 *
 *   # smoke run — 2 cases per (profession, subcategory) from dev = 500 calls
 *   PRONTO_AI_EVAL=true OPENAI_API_KEY=sk-... PRONTO_EVAL_PER_GROUP=2 \
 *     mvn -o test -Dtest=OpenAiTaxonomyEvaluationRunnerTest
 *
 *   # the full development split, 3,500 calls
 *   PRONTO_AI_EVAL=true OPENAI_API_KEY=sk-... PRONTO_EVAL_SPLIT=dev \
 *     mvn -o test -Dtest=OpenAiTaxonomyEvaluationRunnerTest
 * </pre>
 *
 * <p>Environment:
 * <ul>
 *   <li>{@code PRONTO_AI_EVAL=true} — required; without it the whole class is skipped, so a
 *       normal build never spends money or touches the network.</li>
 *   <li>{@code OPENAI_API_KEY} — required.</li>
 *   <li>{@code PRONTO_EVAL_SPLIT} — {@code dev} (default), {@code validation}, {@code holdout}
 *       or {@code all}.</li>
 *   <li>{@code PRONTO_EVAL_PER_GROUP} — stratified subsample size per (profession, subcategory).
 *       Absent means the whole split.</li>
 *   <li>{@code PRONTO_EVAL_OUT} — path for the per-case TSV. Defaults to
 *       {@code target/classification-eval.tsv}.</li>
 *   <li>{@code OPENAI_MODEL} — defaults to the same value {@code application.yml} does, so a run
 *       that sets nothing measures what production runs.</li>
 * </ul>
 *
 * <p><b>An instrument, not a gate.</b> The only assertion is that the pipeline did not break;
 * accuracy is printed, never asserted. A CI job that failed under a threshold would turn the
 * number into something to be satisfied rather than something to learn from, and the first
 * response to a red build would be to trim the dataset.
 *
 * <p><b>On the holdout split.</b> Nothing here prevents {@code PRONTO_EVAL_SPLIT=holdout}, and
 * nothing could. It prints a warning because the cost of using it is invisible at the moment it
 * is paid: once a prompt has been chosen using the holdout, the holdout has become a validation
 * set and there is no un-spending it. Use it to confirm a decision already made, never to make
 * one.
 */
@EnabledIfEnvironmentVariable(named = "PRONTO_AI_EVAL", matches = "true")
class OpenAiTaxonomyEvaluationRunnerTest {

    @Test
    void measureClassificationAccuracyAgainstTheLabelledDataset() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("PRONTO_AI_EVAL is set but OPENAI_API_KEY is missing — skipping.");
            return;
        }

        String model = System.getenv().getOrDefault("OPENAI_MODEL", DEFAULT_MODEL);
        long timeoutMs = Long.parseLong(System.getenv().getOrDefault("OPENAI_TIMEOUT_MS", "30000"));

        ProfessionTaxonomy taxonomy = new ProfessionTaxonomy();
        RoutingProperties properties = new RoutingProperties();
        ServiceCategoryCatalog catalog = new ServiceCategoryCatalog(TestCategories.repository());

        // Constructed before the client so its usage listener can be installed on the transport.
        TaxonomyEvaluator[] evaluatorHolder = new TaxonomyEvaluator[1];
        OpenAiChatClient chatClient = new OpenAiChatClient(apiKey, model, timeoutMs, new ObjectMapper(),
                (schemaName, latency, attempts, prompt, completion, reasoning, ok) ->
                        evaluatorHolder[0].usageListener()
                                .onCall(schemaName, latency, attempts, prompt, completion, reasoning, ok));

        OpenAiClassificationClient client = new OpenAiClassificationClient(
                chatClient,
                catalog,
                new ClassificationPromptBuilder(taxonomy),
                new ClassificationSchema(taxonomy),
                new ProfessionalBriefPromptBuilder(),
                new ProfessionalBriefSchema());

        ClassificationService classificationService = new ClassificationService(client, catalog,
                new RoutingDecisionPolicy(properties, taxonomy),
                new IssueImageResolver(Mockito.mock(StorageClient.class)),
                taxonomy);

        TaxonomyEvaluator evaluator = new TaxonomyEvaluator(classificationService, taxonomy, model);
        evaluatorHolder[0] = evaluator;

        TaxonomyDataset dataset = TaxonomyDataset.load();
        String split = TaxonomyDataset.splitOrDefault(System.getenv("PRONTO_EVAL_SPLIT"));

        List<TaxonomyEvaluationCase> selected = "all".equals(split) ? dataset.cases() : dataset.split(split);
        assertThat(selected).as("split '%s' matched no cases", split).isNotEmpty();

        if (TaxonomyEvaluationCase.SPLIT_HOLDOUT.equals(split)) {
            System.out.println();
            System.out.println("*** HOLDOUT SPLIT ***  Use this to confirm a decision, never to make one.");
            System.out.println("*** Tuning a prompt against these cases silently converts the holdout into");
            System.out.println("*** a validation set, and there is no way to get it back.");
        }

        String perGroup = System.getenv("PRONTO_EVAL_PER_GROUP");
        if (perGroup != null && !perGroup.isBlank()) {
            int size = Integer.parseInt(perGroup.trim());
            List<TaxonomyEvaluationCase> sampled = TaxonomyDataset.stratifiedSample(selected, size);
            // Logged, not silent. A subsampled run that printed the same headline as a full one
            // would be quoted as if it were the full one.
            System.out.printf("subsample: %d per (profession, subcategory) -> %d of %d case(s)%n",
                    size, sampled.size(), selected.size());
            selected = sampled;
        }

        TaxonomyDataset.Manifest manifest = dataset.manifest();
        System.out.println();
        System.out.println("=== run metadata ===");
        System.out.println("promptVersion    " + ClassificationPromptBuilder.PROMPT_VERSION);
        System.out.println("model            " + model);
        System.out.println("taxonomyVersion  " + taxonomy.taxonomyVersion());
        System.out.println("datasetVersion   " + manifest.datasetVersion());
        System.out.println("datasetSha256    " + manifest.datasetSha256());
        System.out.println("splitSalt        " + manifest.splitSalt());
        System.out.println("split            " + split + "  (" + selected.size() + " case(s))");
        System.out.println("taxonomy         " + manifest.professions() + " professions, "
                + manifest.subcategories() + " subcategories, "
                + manifest.dispatchable() + " dispatchable");
        System.out.println("thresholds       minConfidence=" + properties.getMinConfidence()
                + " minCandidateMargin=" + properties.getMinCandidateMargin()
                + " highConfidence=" + properties.getHighConfidence());

        System.out.println("temperature      " + (OpenAiChatClient.supportsCustomTemperature(model)
                ? "0.0 (greedy)"
                : "NOT SENT — this model rejects a custom value; decoding is sampled, so two runs "
                        + "of this configuration can legitimately differ"));

        long runStart = System.nanoTime();
        List<TaxonomyEvaluationOutcome> outcomes = evaluator.run(selected);
        long runMillis = (System.nanoTime() - runStart) / 1_000_000L;

        TaxonomyEvaluationReport report =
                new TaxonomyEvaluationReport(outcomes, properties.getHighConfidence());

        System.out.println();
        System.out.println(report.render());
        System.out.printf("wall clock       %,d ms total%n%n", runMillis);

        System.out.println("--- ESTIMATED COST ---");
        System.out.println("Rates are a CONSTANT in this test, not read from the API. Verify against "
                + "current published pricing before quoting.");
        System.out.println(report.renderCost(INPUT_USD_PER_MILLION, OUTPUT_USD_PER_MILLION));

        System.out.println("--- ACCURACY BY PROFESSION (worst first) ---");
        System.out.println(report.renderByProfession(0));

        System.out.println("--- 10 WORST PROFESSIONS ---");
        System.out.println(report.renderByProfession(10));

        System.out.println(report.renderFailureList(FAILURES_PRINTED));

        System.out.println("--- CORRECT CLASSIFICATION, WRONG DISPATCH ---");
        System.out.println(TaxonomyEvaluationReport.renderCaseLines(
                report.correctClassificationWrongDispatch(), DIAGNOSTIC_LIST_LIMIT));

        System.out.println("--- ASKED A QUESTION UNNECESSARILY ---");
        System.out.println(TaxonomyEvaluationReport.renderCaseLines(
                report.askedUnnecessarily(), DIAGNOSTIC_LIST_LIMIT));

        System.out.println("--- SHOULD HAVE ASKED AND DID NOT ---");
        System.out.println(TaxonomyEvaluationReport.renderCaseLines(
                report.shouldHaveAskedButDidNot(), DIAGNOSTIC_LIST_LIMIT));

        if (!report.errors().isEmpty()) {
            System.out.println("--- ERRORED CASES (excluded from every accuracy figure) ---");
            report.errors().forEach(outcome ->
                    System.out.println(outcome.id() + "\t" + outcome.error()));
            System.out.println();
        }

        Path out = Path.of(System.getenv().getOrDefault("PRONTO_EVAL_OUT", "target/classification-eval.tsv"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, report.renderTsv(), StandardCharsets.UTF_8);
        System.out.println("per-case results -> " + out.toAbsolutePath());

        if (!report.errors().isEmpty()) {
            // Not a failed assertion: a run that reached the model for 95% of its cases still
            // produced 95% of a useful measurement, and discarding it because the provider
            // wobbled would be worse than reporting it with the caveat attached.
            System.out.println();
            System.out.printf("WARNING: %d case(s) errored and are excluded from every accuracy "
                    + "figure above.%n", report.errors().size());
        }

        assertThat(outcomes).as("the harness must produce one outcome per case").hasSameSizeAs(selected);
    }

    /** Enough to see the shape of the failures without burying the metrics above them. */
    private static final int FAILURES_PRINTED = 500;

    private static final int DIAGNOSTIC_LIST_LIMIT = 200;

    /**
     * Kept in step with {@code application.yml}'s {@code pronto.openai.model} so a run that sets
     * no {@code OPENAI_MODEL} measures what production serves. {@code OpenAiModelConfigurationTest}
     * pins the same value in {@code application.yml} and {@code compute.tf}.
     */
    private static final String DEFAULT_MODEL = "gpt-5-mini";

    /**
     * gpt-5-mini list pricing, USD per million tokens.
     *
     * <p><b>A constant, not a measurement.</b> The API returns token counts but not what they
     * cost, so this is the one number in the report nothing verifies. Reasoning tokens bill as
     * output, which on this model is where most of the spend is.
     */
    private static final double INPUT_USD_PER_MILLION = 0.25;
    private static final double OUTPUT_USD_PER_MILLION = 2.00;
}
