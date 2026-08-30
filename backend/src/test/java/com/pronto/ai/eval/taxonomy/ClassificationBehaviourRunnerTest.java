package com.pronto.ai.eval.taxonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.TestCategories;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.client.OpenAiChatClient;
import com.pronto.ai.client.OpenAiClassificationClient;
import com.pronto.ai.decision.RoutingDecisionPolicy;
import com.pronto.ai.decision.RoutingProperties;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link BehaviourCases} against the live model and reports which rules hold.
 *
 * <pre>
 *   PRONTO_AI_EVAL=true OPENAI_API_KEY=sk-... \
 *     mvn -o test -Dtest=ClassificationBehaviourRunnerTest
 * </pre>
 *
 * <p>Fourteen calls, so it is cheap enough to run on every prompt edit — this is the fast
 * feedback loop, and {@link OpenAiTaxonomyEvaluationRunnerTest} is the measurement.
 *
 * <p><b>Reports, does not assert.</b> Same reasoning as the main harness: a red build turns these
 * into rules to be satisfied, and the cheapest way to satisfy a failing behavioural case is to
 * delete it. The one thing asserted is that every case produced a verdict, so a run that silently
 * covered nothing cannot pass.
 */
@EnabledIfEnvironmentVariable(named = "PRONTO_AI_EVAL", matches = "true")
class ClassificationBehaviourRunnerTest {

    @Test
    void reportWhichBehaviouralRulesHold() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("PRONTO_AI_EVAL is set but OPENAI_API_KEY is missing — skipping.");
            return;
        }

        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-5-mini");
        long timeoutMs = Long.parseLong(System.getenv().getOrDefault("OPENAI_TIMEOUT_MS", "30000"));

        ProfessionTaxonomy taxonomy = new ProfessionTaxonomy();
        RoutingProperties properties = new RoutingProperties();
        ServiceCategoryCatalog catalog = new ServiceCategoryCatalog(TestCategories.repository());

        ClassificationService service = new ClassificationService(
                new OpenAiClassificationClient(
                        new OpenAiChatClient(apiKey, model, timeoutMs, new ObjectMapper()),
                        catalog, new ClassificationPromptBuilder(taxonomy),
                        new ClassificationSchema(taxonomy), new ProfessionalBriefPromptBuilder(),
                        new ProfessionalBriefSchema()),
                catalog, new RoutingDecisionPolicy(properties, taxonomy),
                new IssueImageResolver(Mockito.mock(StorageClient.class)), taxonomy);

        System.out.println();
        System.out.println("promptVersion   " + ClassificationPromptBuilder.PROMPT_VERSION);
        System.out.println("model           " + model);
        System.out.println("taxonomyVersion " + taxonomy.taxonomyVersion());
        System.out.println();

        List<String> verdicts = new ArrayList<>();
        int passed = 0;

        for (BehaviourCase testCase : BehaviourCases.all()) {
            List<String> problems = new ArrayList<>();
            String detail;
            try {
                ClassificationSuggestion result =
                        service.classify(testCase.description(), List.of(), null, List.of());
                problems.addAll(check(testCase, result));
                detail = describe(result);
            } catch (Exception e) {
                problems.add("pipeline error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                detail = "(no result)";
            }

            boolean ok = problems.isEmpty();
            if (ok) {
                passed++;
            }
            verdicts.add(String.format("%-28s %-6s %s%s", testCase.name(), ok ? "PASS" : "FAIL", detail,
                    ok ? "" : "\n      " + String.join("\n      ", problems)
                            + "\n      why this case exists: " + testCase.rationale()));
        }

        verdicts.forEach(System.out::println);
        System.out.println();
        System.out.printf("%d/%d behavioural rules hold under %s / %s%n",
                passed, BehaviourCases.all().size(), ClassificationPromptBuilder.PROMPT_VERSION, model);

        assertThat(verdicts).as("every case must produce a verdict")
                .hasSameSizeAs(BehaviourCases.all());
    }

    private static List<String> check(BehaviourCase testCase, ClassificationSuggestion result) {
        List<String> problems = new ArrayList<>();
        boolean asked = result.status() == ClassificationStatus.QUESTIONS;

        if (Boolean.TRUE.equals(testCase.mustAsk()) && !asked) {
            problems.add("expected a clarification question; it committed to "
                    + result.professionCode());
        }
        if (Boolean.FALSE.equals(testCase.mustAsk()) && asked) {
            problems.add("expected a direct answer; it asked a question instead");
        }

        // The forbidden-profession check runs even when the classifier asked: the guard cases are
        // about which trade it leans towards, and leaning at PAINTER while asking is still the
        // failure the guard is there to catch.
        for (String forbidden : testCase.forbiddenProfessions()) {
            if (forbidden.equalsIgnoreCase(result.professionCode())) {
                problems.add("must NOT classify as " + forbidden);
            }
        }

        // Positive expectations are not asserted on a case whose correct behaviour was to ask --
        // there is deliberately no committed profession to compare against.
        if (!asked || Boolean.FALSE.equals(testCase.mustAsk())) {
            if (testCase.expectedProfession() != null
                    && !testCase.expectedProfession().equalsIgnoreCase(result.professionCode())) {
                problems.add("expected profession " + testCase.expectedProfession()
                        + ", got " + result.professionCode());
            }
            if (testCase.expectedSubcategory() != null
                    && !testCase.expectedSubcategory().equalsIgnoreCase(result.subcategoryCode())) {
                problems.add("expected subcategory " + testCase.expectedSubcategory()
                        + ", got " + result.subcategoryCode());
            }
            if (testCase.expectedIntent() != null
                    && (result.intent() == null
                        || !testCase.expectedIntent().equalsIgnoreCase(result.intent().name()))) {
                problems.add("expected intent " + testCase.expectedIntent() + ", got " + result.intent());
            }
            if (testCase.minimumUrgency() != null) {
                int actual = result.urgency() == null ? -1
                        : BehaviourCase.urgencyRank(result.urgency().name());
                if (actual < BehaviourCase.urgencyRank(testCase.minimumUrgency())) {
                    problems.add("expected urgency of at least " + testCase.minimumUrgency()
                            + ", got " + result.urgency());
                }
            }
        }
        return problems;
    }

    private static String describe(ClassificationSuggestion result) {
        return String.format("%s/%s intent=%s urgency=%s dispatch=%s status=%s",
                result.professionCode(), result.subcategoryCode(), result.intent(), result.urgency(),
                result.categoryCode() == null ? "none" : result.categoryCode(), result.status());
    }
}
