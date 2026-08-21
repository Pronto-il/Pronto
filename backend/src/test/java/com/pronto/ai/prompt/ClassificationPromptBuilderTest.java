package com.pronto.ai.prompt;

import com.pronto.ai.TestCategories;
import com.pronto.ai.catalog.CategoryRoutingProfile;
import com.pronto.ai.catalog.CategoryRoutingProfiles;
import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClassificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt is the classifier's specification, so the properties that would silently degrade
 * routing if they regressed are asserted here: every live category is described, the
 * disambiguation rules are present, the customer's hint is framed as a hint, and the
 * budget-exhausted prompt genuinely switches to commit-now mode.
 *
 * <p>Assertions are on structure and key phrases, not on exact wording — otherwise every
 * prompt edit would break the tests and they would be deleted rather than maintained.
 */
class ClassificationPromptBuilderTest {

    private ClassificationPromptBuilder builder;
    private List<ServiceCategory> categories;

    @BeforeEach
    void setUp() {
        builder = new ClassificationPromptBuilder();
        categories = new ServiceCategoryCatalog(TestCategories.repository()).categories();
    }

    @Test
    void everyLiveCategoryAppearsWithItsBoundary() {
        String prompt = builder.buildSystemPrompt(categories, 2);

        assertThat(categories).isNotEmpty();
        for (ServiceCategory category : categories) {
            assertThat(prompt).as("category %s must appear in the prompt", category.code())
                    .contains(category.code());
        }
        assertThat(prompt).contains("Belongs here:", "Does NOT belong here:", "Easily confused with:");
    }

    @Test
    void theDisambiguationRulesForTheHardOverlapsArePresent() {
        String prompt = builder.buildSystemPrompt(categories, 2);

        CategoryRoutingProfile plumbing = CategoryRoutingProfiles.find(CategoryRoutingProfiles.CODE_PLUMBING);
        assertThat(plumbing).isNotNull();
        assertThat(plumbing.confusedWith()).extracting(CategoryRoutingProfile.OverlapRule::otherCategoryCode)
                .contains(CategoryRoutingProfiles.CODE_AC_HVAC, CategoryRoutingProfiles.CODE_ELECTRICAL,
                        CategoryRoutingProfiles.CODE_APPLIANCE_REPAIR);

        assertThat(prompt).contains("vs " + CategoryRoutingProfiles.CODE_AC_HVAC);
        assertThat(prompt).contains("vs " + CategoryRoutingProfiles.CODE_ELECTRICAL);
    }

    @Test
    void theTaskIsFramedAsRoutingNotDiagnosis() {
        String prompt = builder.buildSystemPrompt(categories, 2);

        assertThat(prompt).contains("WHICH PRONTO PROFESSIONAL SHOULD BE SENT");
        assertThat(prompt).contains("routing problem, not a technical diagnosis problem");
    }

    @Test
    void withBudgetRemainingTheModelIsToldToProposeAtMostOneQuestion() {
        String prompt = builder.buildSystemPrompt(categories, 2);

        assertThat(prompt).contains("at most ONE question");
        assertThat(prompt).contains("Questions still allowed for this issue: 2");
        assertThat(prompt).contains("HEBREW");
        // The generic-filler ban is the single biggest quality lever on question text.
        assertThat(prompt).contains("can you explain more?");
    }

    @Test
    void withNoBudgetTheModelIsToldToCommitAndNotToAsk() {
        String prompt = builder.buildSystemPrompt(categories, 0);

        assertThat(prompt).contains("no clarification questions left");
        assertThat(prompt).contains("nextQuestion = null");
        assertThat(prompt).doesNotContain("at most ONE question");
    }

    @Test
    void theEvidenceBlockCarriesTheDescriptionHintAndFullConversation() {
        ClassificationRequest request = new ClassificationRequest("יש מים מתחת לכיור", List.of(), "electrical",
                List.of(new ClarificationExchange("מאיפה המים?", "מהסיפון"),
                        new ClarificationExchange("מתי זה קורה?", "רק בשימוש")),
                1);

        String evidence = builder.buildEvidencePrompt(request, "electrical (Electrical / חשמל)");

        assertThat(evidence).contains("יש מים מתחת לכיור");
        assertThat(evidence).contains("hint only");
        assertThat(evidence).contains("electrical (Electrical / חשמל)");
        assertThat(evidence).contains("מאיפה המים?").contains("מהסיפון");
        assertThat(evidence).contains("מתי זה קורה?").contains("רק בשימוש");
        assertThat(evidence).contains("Do not ask any of the above again");
        assertThat(evidence).contains("CLARIFICATION QUESTIONS STILL ALLOWED: 1");
    }

    @Test
    void theEvidenceBlockSaysSoWhenNothingHasBeenAskedOrSelected() {
        ClassificationRequest request =
                new ClassificationRequest("הכיור סתום", List.of(), null, List.of(), 2);

        String evidence = builder.buildEvidencePrompt(request, null);

        assertThat(evidence).contains("the customer did not select a category");
        assertThat(evidence).contains("no questions have been asked yet");
    }
}
