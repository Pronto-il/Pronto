package com.pronto.ai.eval;

import com.pronto.ai.TestTaxonomy;
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
import com.pronto.storage.client.StorageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real measurement: runs the labelled dataset against live OpenAI and prints the full
 * report. <b>This is the only thing in the codebase that can produce a defensible accuracy
 * number.</b> Until it has been run on a dataset someone trusts, no accuracy claim about this
 * system is supported by evidence.
 *
 * <p>Run it deliberately:
 * <pre>
 *   PRONTO_AI_EVAL=true OPENAI_API_KEY=sk-... \
 *     mvn test -Dtest=OpenAiClassificationEvaluationRunnerTest
 * </pre>
 *
 * <p><b>Why an env-gated test rather than an endpoint.</b> A controller that evaluates a
 * dataset would be a public button that spends money and hammers OpenAI, and would have to be
 * secured, rate-limited and kept out of production routing. A test cannot be reached by a
 * request at all: this class lives under {@code src/test}, is skipped unless
 * {@code PRONTO_AI_EVAL=true}, and is skipped again if no API key is present. Normal builds
 * never touch the network.
 *
 * <p>It asserts almost nothing on purpose — it is an instrument, not a gate. Failing CI on an
 * accuracy threshold would make the number something to satisfy rather than something to
 * learn from. The one assertion is that the pipeline itself did not break.
 */
@EnabledIfEnvironmentVariable(named = "PRONTO_AI_EVAL", matches = "true")
class OpenAiClassificationEvaluationRunnerTest {

    @Test
    void measureRoutingAccuracyAgainstTheLabelledDataset() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("PRONTO_AI_EVAL is set but OPENAI_API_KEY is missing — skipping.");
            return;
        }

        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
        long timeoutMs = Long.parseLong(System.getenv().getOrDefault("OPENAI_TIMEOUT_MS", "30000"));

        RoutingProperties properties = new RoutingProperties();
        ServiceCategoryCatalog catalog = new ServiceCategoryCatalog(TestCategories.repository());

        OpenAiClassificationClient client = new OpenAiClassificationClient(
                new OpenAiChatClient(apiKey, model, timeoutMs, new ObjectMapper()),
                catalog,
                new ClassificationPromptBuilder(TestTaxonomy.taxonomy()),
                new ClassificationSchema(TestTaxonomy.taxonomy()),
                new ProfessionalBriefPromptBuilder(),
                new ProfessionalBriefSchema());

        ClassificationService classificationService = new ClassificationService(client, catalog,
                new RoutingDecisionPolicy(properties, TestTaxonomy.taxonomy()),
                // No image cases in the dataset yet, so storage is never touched. When image
                // cases are added, this is the one dependency that needs a real fixture.
                new IssueImageResolver(Mockito.mock(StorageClient.class)),
                TestTaxonomy.taxonomy());

        EvaluationCases.Dataset dataset = EvaluationCases.dataset();

        // Optional case filter, for running one slice of the set against the live model —
        // an A/B against a different prompt version on exactly the frozen core-24
        // (PRONTO_AI_EVAL_ID_PATTERN=case-0(0[1-9]|1[0-9]|2[0-4])), or one boundary's
        // adversarial cases while iterating on it. Absent, the whole dataset runs.
        String idPattern = System.getenv("PRONTO_AI_EVAL_ID_PATTERN");
        List<EvaluationCase> selected = dataset.cases();
        if (idPattern != null && !idPattern.isBlank()) {
            selected = selected.stream().filter(testCase -> testCase.id().matches(idPattern)).toList();
            System.out.println("case filter: " + idPattern + " -> " + selected.size() + " of "
                    + dataset.cases().size() + " case(s)");
            assertThat(selected).as("the case filter matched nothing").isNotEmpty();
        }

        List<EvaluationOutcome> outcomes =
                new ClassificationEvaluator(classificationService, properties.getMaxClarificationQuestions())
                        .run(selected, TestCategories.IDS_BY_CODE);

        // Every number below is meaningless without these three identifiers. Printed first,
        // together, so a pasted result can always be traced back to what produced it.
        System.out.println();
        System.out.println("=== run metadata ===");
        System.out.println("promptVersion   " + ClassificationPromptBuilder.PROMPT_VERSION);
        System.out.println("model           " + model);
        System.out.println("datasetVersion  " + dataset.version());
        System.out.println("datasetSize     " + dataset.cases().size()
                + " (core=" + dataset.core().size() + ", challenge=" + dataset.challenge().size() + ")");
        System.out.println("thresholds      maxQuestions=" + properties.getMaxClarificationQuestions()
                + " minConfidence=" + properties.getMinConfidence()
                + " minCandidateMargin=" + properties.getMinCandidateMargin()
                + " plausibleCandidate=" + properties.getPlausibleCandidateConfidence()
                + " highConfidence=" + properties.getHighConfidence());

        List<EvaluationOutcome> core = outcomes.stream().filter(EvaluationOutcome::isCore).toList();
        List<EvaluationOutcome> challenge = outcomes.stream().filter(outcome -> !outcome.isCore()).toList();

        // The approved regression set carries the >= 95% target on its own. Challenge cases are
        // reported beside it and never folded into it — mixing them would let a hard case be
        // quietly dropped to lift the headline, which is the exact failure §23 warns about.
        EvaluationReport coreReport = new EvaluationReport(core, properties.getHighConfidence());
        System.out.println();
        System.out.println("################ CORE (approved regression set — the MS3 target) ################");
        System.out.println(coreReport.render());

        if (!challenge.isEmpty()) {
            EvaluationReport challengeReport = new EvaluationReport(challenge, properties.getHighConfidence());
            System.out.println();
            System.out.println("################ CHALLENGE (adversarial / multi-trade — reported separately) "
                    + "################");
            System.out.println(challengeReport.render());
        }

        EvaluationReport wholeReport = new EvaluationReport(outcomes, properties.getHighConfidence());
        System.out.println();
        System.out.println("################ ALL CASES ################");
        System.out.println(wholeReport.render());
        System.out.println(wholeReport.renderQuestionQuality());

        assertThat(wholeReport.failures())
                .as("the pipeline itself must not error; accuracy is reported, not asserted")
                .isEmpty();
    }
}
