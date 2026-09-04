package com.pronto.ai.eval;

import com.pronto.ai.TestTaxonomy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.TestCategories;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.client.OpenAiChatClient;
import com.pronto.ai.client.OpenAiClassificationClient;
import com.pronto.ai.decision.RoutingDecisionPolicy;
import com.pronto.ai.decision.RoutingProperties;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import com.pronto.ai.prompt.ClassificationPromptBuilder;
import com.pronto.ai.prompt.ClassificationSchema;
import com.pronto.ai.prompt.ProfessionalBriefPromptBuilder;
import com.pronto.ai.prompt.ProfessionalBriefSchema;
import com.pronto.ai.service.ClassificationService;
import com.pronto.ai.service.IssueImageResolver;
import com.pronto.ai.service.ProfessionalBriefService;
import com.pronto.storage.client.StorageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Professional Brief evaluation against live OpenAI: classify a real case, run its
 * clarification rounds, then generate the brief the professional would actually receive and
 * score it with {@link BriefQualityRubric}.
 *
 * <p><b>Why this exists separately from the routing runner.</b> Routing accuracy and brief
 * quality fail in completely different ways. A brief can be attached to a perfectly routed job
 * and still be useless — or worse, misleading, by asserting a diagnosis nobody could have made
 * without visiting. Measuring only the category would never surface that.
 *
 * <p>Cases are drawn from the same labelled dataset, so a brief is always generated for the
 * same evidence and the same category the routing evaluation scored. Cases with scripted
 * clarification answers are deliberately included: the brief's handling of answered questions
 * is a distinct behaviour from its handling of a bare description.
 *
 * <pre>
 *   PRONTO_AI_EVAL_BRIEF=true OPENAI_API_KEY=sk-... \
 *     mvn test -Dtest=OpenAiProfessionalBriefEvaluationRunnerTest
 * </pre>
 *
 * <p>Asserts only that the pipeline did not break and that no brief invented image observations
 * for a job with no photos — the one rubric failure that is a hard correctness bug rather than
 * a quality judgement. Everything else is reported for a human to read.
 */
@EnabledIfEnvironmentVariable(named = "PRONTO_AI_EVAL_BRIEF", matches = "true")
class OpenAiProfessionalBriefEvaluationRunnerTest {

    /**
     * A representative slice rather than the whole set: briefs are read one at a time, and
     * every extra case is a second paid call for a diminishing amount of signal. Chosen to
     * cover every category, both clarification paths, and the two cases where the correct brief
     * is a cautious one (the contentless fallback, and the injection attempt).
     */
    private static final List<String> SAMPLE_CASE_IDS = List.of(
            "case-001",   // plumbing, clear, no questions
            "case-005",   // ac_hvac, electrical symptom on an AC component
            "case-009",   // appliance_repair, WITH clarification
            "case-010",   // plumbing, same opening text, opposite answer
            "case-012",   // electrical, safety-relevant
            "case-024",   // general_handyman, contentless -> brief must stay cautious
            "case-032",   // appliance_repair, clear
            "case-036",   // painting, purely decorative
            "case-046",   // plumbing, very long rambling description
            "case-061",   // electrical, immediate hazard -> safety notes expected
            "case-063",   // plumbing, prompt injection attempt
            "case-071");  // locksmith, door boundary WITH clarification

    /**
     * Everything printed is also written here, in explicit UTF-8.
     *
     * <p>Not a convenience: on Windows the JVM's stdout encoding is the console code page, so
     * piping this run to a file mangles every Hebrew character in it. The evidence produced by
     * a Hebrew-language evaluation has to survive being read back, and redirected stdout does
     * not achieve that. Set {@code PRONTO_AI_EVAL_OUT} to choose the path.
     */
    private final StringBuilder transcript = new StringBuilder();

    private void emit(String line) {
        System.out.println(line);
        transcript.append(line).append(System.lineSeparator());
    }

    private void writeTranscript() {
        String path = System.getenv().getOrDefault("PRONTO_AI_EVAL_OUT", "brief-evaluation.txt");
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), transcript.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("transcript written (UTF-8): " + java.nio.file.Path.of(path).toAbsolutePath());
        } catch (Exception e) {
            System.out.println("could not write transcript: " + e.getMessage());
        }
    }

    @Test
    void generateAndScoreProfessionalBriefsAgainstTheRealModel() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("PRONTO_AI_EVAL_BRIEF is set but OPENAI_API_KEY is missing — skipping.");
            return;
        }

        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
        long timeoutMs = Long.parseLong(System.getenv().getOrDefault("OPENAI_TIMEOUT_MS", "60000"));

        RoutingProperties properties = new RoutingProperties();
        ServiceCategoryCatalog catalog = new ServiceCategoryCatalog(TestCategories.repository());
        IssueImageResolver imageResolver = new IssueImageResolver(Mockito.mock(StorageClient.class));

        OpenAiClassificationClient client = new OpenAiClassificationClient(
                new OpenAiChatClient(apiKey, model, timeoutMs, new ObjectMapper()),
                catalog,
                new ClassificationPromptBuilder(TestTaxonomy.taxonomy()),
                new ClassificationSchema(TestTaxonomy.taxonomy()),
                new ProfessionalBriefPromptBuilder(),
                new ProfessionalBriefSchema());

        ClassificationService classificationService =
                new ClassificationService(client, catalog, new RoutingDecisionPolicy(properties, TestTaxonomy.taxonomy()),
                        imageResolver, TestTaxonomy.taxonomy());
        ProfessionalBriefService briefService = new ProfessionalBriefService(client, catalog, imageResolver);

        EvaluationCases.Dataset dataset = EvaluationCases.dataset();
        Map<String, EvaluationCase> byId = dataset.cases().stream()
                .collect(java.util.stream.Collectors.toMap(EvaluationCase::id, testCase -> testCase));

        emit("");
        emit("=== professional brief evaluation ===");
        emit("promptVersion   " + ClassificationPromptBuilder.PROMPT_VERSION);
        emit("model           " + model);
        emit("datasetVersion  " + dataset.version());
        emit("sample          " + SAMPLE_CASE_IDS.size() + " case(s)");

        List<BriefQualityRubric.Result> allResults = new ArrayList<>();
        int briefsGenerated = 0;
        int callsUsed = 0;

        for (String caseId : SAMPLE_CASE_IDS) {
            EvaluationCase testCase = byId.get(caseId);
            if (testCase == null) {
                System.out.println("\n" + caseId + " — not in the dataset, skipped");
                continue;
            }

            // Drive the real clarification loop first, so the brief sees exactly the evidence a
            // professional's brief would have been generated from in production.
            List<ClarificationExchange> exchanges = new ArrayList<>();
            ClassificationSuggestion suggestion = null;
            for (int round = 0; round <= properties.getMaxClarificationQuestions(); round++) {
                suggestion = classificationService.classify(testCase.description(), List.of(), null, exchanges);
                callsUsed++;
                if (suggestion.status() == ClassificationStatus.CLASSIFIED) {
                    break;
                }
                ClarificationQuestion question = suggestion.questions().get(0);
                exchanges.add(new ClarificationExchange(question.question(),
                        scriptedAnswer(testCase, question)));
            }

            Long categoryId = TestCategories.IDS_BY_CODE.get(suggestion.categoryCode());
            ProfessionalBriefResponse brief = briefService.generate(testCase.description(), List.of(),
                    categoryId, "STANDARD", exchanges);
            callsUsed++;
            briefsGenerated++;

            List<BriefQualityRubric.Result> results =
                    BriefQualityRubric.evaluate(brief, testCase.description(), exchanges, false);
            allResults.addAll(results);

            print(testCase, suggestion, exchanges, brief, results);
        }

        long failures = allResults.stream().filter(BriefQualityRubric.Result::isFailure).count();
        long passes = allResults.stream().filter(result -> Boolean.TRUE.equals(result.passed())).count();

        System.out.println();
        emit("=== rubric summary ===");
        emit(String.format("briefs generated %d   checks: %d pass / %d fail / %d n-a   AI calls %d",
                briefsGenerated, passes, failures,
                allResults.size() - passes - failures, callsUsed));
        for (BriefQualityRubric.Check check : BriefQualityRubric.Check.values()) {
            long checkFails = allResults.stream()
                    .filter(result -> result.check() == check && result.isFailure()).count();
            long checkRuns = allResults.stream()
                    .filter(result -> result.check() == check && result.passed() != null).count();
            emit(String.format("  %-34s %d/%d passed", check, checkRuns - checkFails, checkRuns));
        }

        writeTranscript();

        // The one rubric item that is a correctness bug rather than a quality judgement:
        // describing a photo that was never sent is fabricated evidence.
        assertThat(allResults.stream()
                .filter(result -> result.check() == BriefQualityRubric.Check.NO_INVENTED_IMAGE_OBSERVATIONS)
                .filter(BriefQualityRubric.Result::isFailure))
                .as("no brief may describe photos that were never supplied")
                .isEmpty();
        assertThat(briefsGenerated).isPositive();
    }

    private String scriptedAnswer(EvaluationCase testCase, ClarificationQuestion question) {
        String normalized = question.question().toLowerCase(Locale.ROOT);
        return testCase.clarificationAnswers().entrySet().stream()
                .filter(entry -> normalized.contains(entry.getKey().toLowerCase(Locale.ROOT)))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> question.options().stream()
                        .filter(option -> option.contains("לא בטוח") || option.contains("לא יודע"))
                        .findFirst()
                        .orElse(question.options().isEmpty()
                                ? "לא בטוח" : question.options().get(question.options().size() - 1)));
    }

    private void print(EvaluationCase testCase, ClassificationSuggestion suggestion,
                        List<ClarificationExchange> exchanges, ProfessionalBriefResponse brief,
                        List<BriefQualityRubric.Result> results) {
        emit("");
        emit("──────────────────────────────────────────────────────────────────");
        emit(testCase.id() + "  routed=" + suggestion.categoryCode()
                + "  expected=" + testCase.expectedCategory()
                + "  questions=" + exchanges.size());
        emit("  CUSTOMER SAID: " + testCase.description());
        exchanges.forEach(exchange ->
                emit("  Q: " + exchange.question() + "\n  A: " + exchange.answer()));
        emit("");
        emit("  --- BRIEF ---");
        emit("  summary:        " + brief.customerProblemSummary());
        emit("  clarification:  " + brief.clarificationSummary());
        if (brief.likelyIssue() != null) {
            emit("  likely issue:   " + brief.likelyIssue().description()
                    + "  (confidence " + brief.likelyIssue().confidence() + ")");
            brief.likelyIssue().evidence().forEach(item -> emit("      evidence: " + item));
        } else {
            emit("  likely issue:   (none offered)");
        }
        emit("  causes:         " + brief.possibleCauses());
        emit("  tools:          " + brief.recommendedTools());
        emit("  parts:          " + brief.recommendedParts());
        emit("  safety:         " + brief.safetyNotes());
        emit("  image obs:      " + brief.imageObservations());
        emit("");
        emit("  --- RUBRIC ---");
        emit(BriefQualityRubric.render(results).stripTrailing());
    }
}
