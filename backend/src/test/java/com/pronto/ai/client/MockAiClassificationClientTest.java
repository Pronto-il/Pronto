package com.pronto.ai.client;

import com.pronto.ai.TestCategories;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClassificationRequest;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.ai.dto.ProfessionalBriefRequest;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default no-key client. It exists so local development and the deterministic parts of the
 * test suite exercise the real pipeline shape rather than a stub, so what matters here is that
 * it emits genuine candidates, can produce a clarification question, and respects the budget —
 * not that its Hebrew keyword heuristic is clever.
 */
class MockAiClassificationClientTest {

    private MockAiClassificationClient client;

    @BeforeEach
    void setUp() {
        client = new MockAiClassificationClient(new ServiceCategoryCatalog(TestCategories.repository()));
    }

    private ClassificationRequest request(String description, int budget, ClarificationExchange... exchanges) {
        return new ClassificationRequest(description, List.of(), null, List.of(exchanges), budget);
    }

    @Test
    void aClearKeywordProducesASingleStrongCandidate() {
        ClassificationResponse response = client.classify(request("יש נזילה מהברז במטבח", 2));

        assertThat(response.primaryCategoryCode()).isEqualTo("plumbing");
        assertThat(response.candidates()).extracting(CategoryCandidate::categoryCode).contains("plumbing");
        assertThat(response.needsClarification()).isFalse();
    }

    @Test
    void noKeywordMatchFallsBackToTheSeededHandymanCategory() {
        ClassificationResponse response = client.classify(request("משהו לא ברור קרה אתמול בערב", 2));

        assertThat(response.primaryCategoryCode()).isEqualTo(ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE);
        assertThat(response.needsClarification()).isFalse();
    }

    @Test
    void competingKeywordsProduceCloseCandidatesAndAQuestion() {
        // "מים" scores plumbing, "מזגן" scores ac_hvac — one hit each, so the margin is zero.
        ClassificationResponse response = client.classify(request("יש מים מתחת למזגן", 2));

        assertThat(response.candidates()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(response.needsClarification()).isTrue();
        assertThat(response.nextQuestion()).isNotNull();
        assertThat(response.nextQuestion().options()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(response.nextQuestion().distinguishesBetween()).contains("ac_hvac", "plumbing");
    }

    @Test
    void noQuestionIsProposedOnceTheBudgetIsSpent() {
        ClassificationResponse response = client.classify(request("יש מים מתחת למזגן", 0));

        assertThat(response.needsClarification()).isFalse();
        assertThat(response.nextQuestion()).isNull();
        assertThat(response.primaryCategoryCode()).isNotNull();
    }

    @Test
    void aClarificationAnswerShiftsTheScoringOnTheNextPass() {
        ClassificationResponse before = client.classify(request("יש מים מתחת למזגן", 2));
        assertThat(before.needsClarification()).isTrue();

        ClassificationResponse after = client.classify(request("יש מים מתחת למזגן", 1,
                new ClarificationExchange("מאיפה מגיעים המים?", "מהמאייד של המזגן, טפטוף קונדנס")));

        assertThat(after.primaryCategoryCode()).isEqualTo("ac_hvac");
        assertThat(after.needsClarification()).isFalse();
    }

    @Test
    void aSpecialistKeywordBeatsAHandymanKeyword() {
        // "ארון" alone is handyman work; adding a leak must not leave it there.
        ClassificationResponse response = client.classify(request("יש נזילת מים בתוך הארון במטבח", 0));

        assertThat(response.primaryCategoryCode()).isEqualTo("plumbing");
    }

    @Test
    void theBriefNeverClaimsImageObservationsBecauseMockModeHasNoVision() {
        ProfessionalBriefResponse brief = client.generateBrief(new ProfessionalBriefRequest(
                "יש נזילה מתחת לכיור", List.of(), "plumbing", "אינסטלציה", "STANDARD",
                List.of(new ClarificationExchange("מאיפה המים?", "מהסיפון"))));

        assertThat(brief.imageObservations()).isEmpty();
        assertThat(brief.customerProblemSummary()).startsWith("[מוק]");
        assertThat(brief.likelyIssue().evidence()).isNotEmpty();
        assertThat(brief.clarificationSummary()).isNotNull();
    }

    @Test
    void theBriefOmitsAClarificationSummaryWhenNothingWasAsked() {
        ProfessionalBriefResponse brief = client.generateBrief(new ProfessionalBriefRequest(
                "יש נזילה מתחת לכיור", List.of(), "plumbing", "אינסטלציה", "STANDARD", List.of()));

        assertThat(brief.clarificationSummary()).isNull();
    }
}
