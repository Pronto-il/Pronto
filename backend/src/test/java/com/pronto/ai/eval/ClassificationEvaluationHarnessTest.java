package com.pronto.ai.eval;

import com.pronto.ai.TestTaxonomy;
import com.pronto.ai.TestCategories;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.client.MockAiClassificationClient;
import com.pronto.ai.decision.RoutingDecisionPolicy;
import com.pronto.ai.decision.RoutingProperties;
import com.pronto.ai.service.ClassificationService;
import com.pronto.ai.service.IssueImageResolver;
import com.pronto.storage.client.StorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the whole labelled dataset through the real pipeline in {@code mock} AI mode — no
 * network, no key, fully deterministic, and part of the normal build.
 *
 * <p>This is a test of the <b>harness and the pipeline</b>, not of accuracy. The mock is a
 * keyword heuristic and will get plenty of these cases wrong; asserting an accuracy floor
 * here would either be trivially low or would break whenever a keyword list changes.
 *
 * <p>What it does lock in is everything measurement depends on and that a model cannot fix:
 * every case terminates, no case exceeds the configured question budget, no case throws, and
 * the report computes. If the clarification loop ever stops being bounded, this test fails
 * without anyone spending a token.
 *
 * <p>The real, network-hitting measurement lives in
 * {@link OpenAiClassificationEvaluationRunnerTest}, which is opt-in via an environment
 * variable.
 */
class ClassificationEvaluationHarnessTest {

    private RoutingProperties properties;
    private ClassificationEvaluator evaluator;

    @BeforeEach
    void setUp() {
        properties = new RoutingProperties();
        ServiceCategoryCatalog catalog = new ServiceCategoryCatalog(TestCategories.repository());
        ClassificationService classificationService = new ClassificationService(
                new MockAiClassificationClient(catalog, TestTaxonomy.taxonomy()),
                catalog,
                new RoutingDecisionPolicy(properties, TestTaxonomy.taxonomy()),
                new IssueImageResolver(Mockito.mock(StorageClient.class)),
                TestTaxonomy.taxonomy());

        evaluator = new ClassificationEvaluator(classificationService, properties.getMaxClarificationQuestions());
    }

    @Test
    void everyLabelledCaseTerminatesWithinTheClarificationBudget() {
        List<EvaluationCase> cases = EvaluationCases.load();
        assertThat(cases).isNotEmpty();

        List<EvaluationOutcome> outcomes = evaluator.run(cases, TestCategories.IDS_BY_CODE);

        assertThat(outcomes).hasSameSizeAs(cases);
        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.failureReason()).as("case %s must not fail", outcome.caseId()).isNull();
            // Every case must TERMINATE; not every case terminates with a category. An
            // unsupported-profession outcome is a complete, correct answer that deliberately
            // carries none — asserting a category here would have made the new state untestable
            // and would have quietly required the fallback this change removes.
            if (!outcome.unsupportedProfession()) {
                assertThat(outcome.finalCategory()).as("case %s must reach a routing decision", outcome.caseId())
                        .isNotNull();
            }
            assertThat(outcome.questionsAsked())
                    .as("case %s must stay within the clarification budget", outcome.caseId())
                    .isLessThanOrEqualTo(properties.getMaxClarificationQuestions());
        });
    }

    @Test
    void everyRoutedCategoryIsARealProntoCategory() {
        List<EvaluationOutcome> outcomes = evaluator.run(EvaluationCases.load(), TestCategories.IDS_BY_CODE);

        assertThat(outcomes).allSatisfy(outcome -> {
            if (outcome.unsupportedProfession()) {
                // The point of the state: no category at all, rather than an invented one or the
                // nearest real one.
                assertThat(outcome.finalCategory())
                        .as("case %s is unsupported and must carry no category", outcome.caseId())
                        .isNull();
                return;
            }
            assertThat(TestCategories.IDS_BY_CODE)
                    .as("case %s routed to an unknown category", outcome.caseId())
                    .containsKey(outcome.finalCategory());
        });
    }

    @Test
    void reportComputesEveryRequiredMetric() {
        EvaluationReport report = new EvaluationReport(
                evaluator.run(EvaluationCases.load(), TestCategories.IDS_BY_CODE),
                properties.getHighConfidence());

        assertThat(report.total()).isGreaterThan(0);
        assertThat(report.finalAccuracy()).isBetween(0.0, 1.0);
        assertThat(report.initialAccuracy()).isBetween(0.0, 1.0);
        assertThat(report.clarificationRate()).isBetween(0.0, 1.0);
        assertThat(report.averageQuestions()).isGreaterThanOrEqualTo(0);
        assertThat(report.perCategoryAccuracy()).isNotEmpty();
        assertThat(report.render()).contains("FINAL accuracy");
        assertThat(report.render()).contains("unsupported-profession accuracy");
        assertThat(report.unsupportedCases()).isNotEmpty();
        assertThat(report.unsupportedAccuracy()).isBetween(0.0, 1.0);

        // Printed so a local run of this test is also a usable (if mock-quality) report.
        System.out.println(report.render());
    }
}
