package com.pronto.ai.service;

import com.pronto.ai.TestTaxonomy;
import com.pronto.ai.TestCategories;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.client.AiClassificationClient;
import com.pronto.ai.decision.RoutingDecisionPolicy;
import com.pronto.ai.decision.RoutingProperties;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationRequest;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.storage.client.StorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The routing pipeline end to end, with the AI stubbed.
 *
 * <p>Covers the product scenarios the redesign is judged on — straightforward routing for
 * each of the common trades, the ambiguous cases where asking beats guessing, the customer
 * who picked the wrong category, and the two loop-safety properties (no duplicate question,
 * no unbounded clarification). The model's own judgment is stubbed on purpose: whether OpenAI
 * spots a boiler-versus-electrical distinction is measured by the labelled evaluation harness,
 * whereas what this file locks in is that the application does the right thing with whatever
 * the model returns.
 */
class ClassificationServiceTest {

    private AiClassificationClient client;
    private RoutingProperties properties;
    private ClassificationService classificationService;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(AiClassificationClient.class);
        properties = new RoutingProperties();
        ServiceCategoryCatalog catalog = new ServiceCategoryCatalog(TestCategories.repository());
        classificationService = new ClassificationService(client, catalog,
                new RoutingDecisionPolicy(properties, TestTaxonomy.taxonomy()),
                new IssueImageResolver(Mockito.mock(StorageClient.class)),
                TestTaxonomy.taxonomy());
    }

    private ClassificationResponse confident(String code) {
        return new ClassificationResponse("אינסטלטור", code, 0.95, false, null,
                List.of(new CategoryCandidate(code, 0.95)), null);
    }

    private ClassificationResponse ambiguous(String first, String second, String questionText) {
        return new ClassificationResponse("אינסטלטור", first, 0.5, true, "Source of the fault is unclear.",
                List.of(new CategoryCandidate(first, 0.5), new CategoryCandidate(second, 0.45)),
                new ClarificationQuestion("q", questionText,
                        List.of("אפשרות א", "אפשרות ב", "אני לא בטוח/ה"), List.of(first, second)));
    }

    private ClassificationSuggestion classify(String description) {
        return classificationService.classify(description, List.of(), null, List.of());
    }

    // -- straightforward routing ------------------------------------------------------------

    @Test
    void aBlockedKitchenSinkRoutesToPlumbing() {
        when(client.classify(any())).thenReturn(confident("plumbing"));

        ClassificationSuggestion suggestion = classify("הכיור במטבח סתום");

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.categoryCode()).isEqualTo("plumbing");
        assertThat(suggestion.categoryId()).isEqualTo(1L);
        assertThat(suggestion.lowConfidence()).isFalse();
        assertThat(suggestion.questions()).isEmpty();
    }

    @Test
    void anAcThatRunsButDoesNotCoolRoutesToAcHvac() {
        when(client.classify(any())).thenReturn(confident("ac_hvac"));

        ClassificationSuggestion suggestion = classify("המזגן עובד אבל לא מקרר");

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.categoryCode()).isEqualTo("ac_hvac");
    }

    @Test
    void aWaterHeaterThatDoesNotHeatRoutesToPlumbing() {
        // Pronto has no separate boiler category — water-heater work is plumbing.
        when(client.classify(any())).thenReturn(confident("plumbing"));

        ClassificationSuggestion suggestion = classify("אין מים חמים והדוד לא מחמם");

        assertThat(suggestion.categoryCode()).isEqualTo("plumbing");
        assertThat(suggestion.questions()).isEmpty();
    }

    // -- ambiguity -------------------------------------------------------------------------

    @Test
    void waterNextToTheWaterHeaterAsksInsteadOfCommitting() {
        when(client.classify(any()))
                .thenReturn(ambiguous("plumbing", "electrical", "מה בדיוק רטוב ליד הדוד?"));

        ClassificationSuggestion suggestion = classify("יש מים ליד הדוד");

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.QUESTIONS);
        assertThat(suggestion.categoryCode()).isNull();
        assertThat(suggestion.questions()).hasSize(1);
        assertThat(suggestion.questions().get(0).question()).isEqualTo("מה בדיוק רטוב ליד הדוד?");
    }

    @Test
    void aBreakerTrippingWithTheAcAsksWhetherTheFaultIsAcSpecific() {
        when(client.classify(any()))
                .thenReturn(ambiguous("ac_hvac", "electrical", "המפסק קופץ גם עם מכשירים אחרים?"));

        ClassificationSuggestion suggestion = classify("המפסק קופץ כשאני מדליק את המזגן");

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.QUESTIONS);
        assertThat(suggestion.questions().get(0).options()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void onlyOneQuestionIsEverReturnedAtATime() {
        when(client.classify(any()))
                .thenReturn(ambiguous("plumbing", "ac_hvac", "מאיפה מגיעים המים?"));

        assertThat(classify("יש מים על הרצפה").questions()).hasSize(1);
    }

    // -- the customer's category is a hint, not ground truth ---------------------------------

    @Test
    void theCustomerSelectedCategoryIsPassedAsAHintAndDoesNotOverrideTheEvidence() {
        when(client.classify(any())).thenReturn(confident("plumbing"));

        ClassificationSuggestion suggestion = classificationService.classify(
                "האסלה סתומה", List.of(), TestCategories.IDS_BY_CODE.get("electrical"), List.of());

        ArgumentCaptor<ClassificationRequest> captor = ArgumentCaptor.forClass(ClassificationRequest.class);
        Mockito.verify(client).classify(captor.capture());

        assertThat(captor.getValue().customerSelectedCategoryCode()).isEqualTo("electrical");
        assertThat(suggestion.categoryCode()).isEqualTo("plumbing");
    }

    // -- accumulated context ----------------------------------------------------------------

    @Test
    void everyPassReceivesTheFullAccumulatedContextNotJustTheNewestAnswer() {
        when(client.classify(any())).thenReturn(confident("ac_hvac"));

        List<ClarificationExchange> answers = List.of(
                new ClarificationExchange("שאלה ראשונה", "תשובה ראשונה"),
                new ClarificationExchange("שאלה שנייה", "תשובה שנייה"));

        classificationService.classify("תיאור מקורי", List.of(), null, answers);

        ArgumentCaptor<ClassificationRequest> captor = ArgumentCaptor.forClass(ClassificationRequest.class);
        Mockito.verify(client).classify(captor.capture());

        assertThat(captor.getValue().description()).isEqualTo("תיאור מקורי");
        assertThat(captor.getValue().priorExchanges()).isEqualTo(answers);
    }

    @Test
    void theQuestionBudgetShrinksAsAnswersAccumulateAndReachesZero() {
        properties.setMaxClarificationQuestions(2);
        when(client.classify(any())).thenReturn(confident("plumbing"));

        classificationService.classify("תיאור", List.of(), null, List.of());
        classificationService.classify("תיאור", List.of(), null,
                List.of(new ClarificationExchange("ש1", "ת1")));
        classificationService.classify("תיאור", List.of(), null,
                List.of(new ClarificationExchange("ש1", "ת1"), new ClarificationExchange("ש2", "ת2")));

        ArgumentCaptor<ClassificationRequest> captor = ArgumentCaptor.forClass(ClassificationRequest.class);
        Mockito.verify(client, Mockito.times(3)).classify(captor.capture());

        assertThat(captor.getAllValues()).extracting(ClassificationRequest::clarificationBudgetRemaining)
                .containsExactly(2, 1, 0);
    }

    // -- loop safety ------------------------------------------------------------------------

    @Test
    void theClarificationLoopTerminatesEvenIfTheModelKeepsAskingForever() {
        properties.setMaxClarificationQuestions(2);
        // A model that ignores the budget entirely and always demands another question. The
        // questions are deliberately unrelated to each other, so duplicate suppression cannot
        // be what stops the loop — the budget has to be.
        List<String> endlessQuestions = List.of(
                "מאיפה בדיוק מגיעים המים?",
                "האם המזגן פועל כשזה קורה?",
                "האם הרטיבות מופיעה גם בקיר החיצוני?",
                "מתי הבחנת בבעיה לראשונה?",
                "האם ניסית לסגור את ברז הראשי?");
        when(client.classify(any())).thenAnswer(invocation -> {
            int round = ((ClassificationRequest) invocation.getArgument(0)).priorExchanges().size();
            return ambiguous("plumbing", "ac_hvac", endlessQuestions.get(round % endlessQuestions.size()));
        });

        List<ClarificationExchange> answers = new ArrayList<>();
        ClassificationSuggestion suggestion = null;
        for (int round = 0; round < 10; round++) {
            suggestion = classificationService.classify("תיאור", List.of(), null, answers);
            if (suggestion.status() == ClassificationStatus.CLASSIFIED) {
                break;
            }
            answers.add(new ClarificationExchange(suggestion.questions().get(0).question(), "תשובה"));
        }

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.lowConfidence()).isTrue();
        assertThat(answers).hasSize(properties.getMaxClarificationQuestions());
    }

    @Test
    void aQuestionThatRepeatsAnAnsweredOneIsNotAskedAgain() {
        when(client.classify(any()))
                .thenReturn(ambiguous("plumbing", "ac_hvac", "מאיפה מגיעים המים?"));

        ClassificationSuggestion suggestion = classificationService.classify("יש מים", List.of(), null,
                List.of(new ClarificationExchange("מאיפה מגיעים המים?", "מהמזגן")));

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.lowConfidence()).isTrue();
        assertThat(suggestion.questions()).isEmpty();
    }

    // -- failure handling --------------------------------------------------------------------

    @Test
    void anAiFailureSurfacesAsAiServiceErrorRatherThanARandomCategory() {
        when(client.classify(any()))
                .thenThrow(new ApiException(ErrorCode.AI_SERVICE_ERROR, "boom"));

        assertThatThrownBy(() -> classify("תיאור"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(ErrorCode.AI_SERVICE_ERROR);
    }

    @Test
    void anUnexpectedClientExceptionIsNormalisedNotLeaked() {
        when(client.classify(any())).thenThrow(new IllegalStateException("internal detail"));

        assertThatThrownBy(() -> classify("תיאור"))
                .isInstanceOf(ApiException.class)
                .hasMessageNotContaining("internal detail")
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(ErrorCode.AI_SERVICE_ERROR);
    }
}
