package com.pronto.ai.service;

import com.pronto.ai.client.AiClassificationClient;
import com.pronto.ai.client.ClarificationAnswer;
import com.pronto.ai.client.ClassificationResult;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.professionals.entity.Category;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.storage.client.StorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Orchestration tests for the clarification-question extension (§2.1 of
 * {@code docs/architecture/api-contract-issues.md}): category resolution/fallback is
 * unaffected for a {@code CLASSIFIED} result, a {@code QUESTIONS} result is passed straight
 * through with no category resolution attempted, and {@code classifyWithClarification}
 * performs the single allowed clarification round. The underlying {@link AiClassificationClient}
 * is stubbed directly (not the real OpenAI client) since the description/image-contradiction
 * *detection* itself is OpenAI's job, exercised separately in
 * {@code OpenAiClassificationClientTest}.
 */
class ClassificationServiceTest {

    private AiClassificationClient aiClassificationClient;
    private CategoryRepository categoryRepository;
    private ClassificationService classificationService;

    private Category plumbing;
    private Category generalHandyman;

    @BeforeEach
    void setUp() {
        aiClassificationClient = Mockito.mock(AiClassificationClient.class);
        categoryRepository = Mockito.mock(CategoryRepository.class);
        StorageClient storageClient = Mockito.mock(StorageClient.class);
        classificationService = new ClassificationService(aiClassificationClient, storageClient, categoryRepository);

        plumbing = Mockito.mock(Category.class);
        when(plumbing.getId()).thenReturn(1L);
        when(plumbing.getCode()).thenReturn("plumbing");

        generalHandyman = Mockito.mock(Category.class);
        when(generalHandyman.getId()).thenReturn(8L);
        when(generalHandyman.getCode()).thenReturn("general_handyman");
    }

    @Test
    void classify_clearDescriptionAndImage_returnsClassified() {
        when(categoryRepository.findAll()).thenReturn(List.of(plumbing, generalHandyman));
        when(aiClassificationClient.classify(anyString(), anyList())).thenReturn(
                new ClassificationResult(ClassificationStatus.CLASSIFIED, "plumbing", 0.96,
                        "Leaking pipe under the sink.", List.of()));

        ClassificationSuggestion suggestion = classificationService.classify("description", List.of("key.jpg"));

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.categoryId()).isEqualTo(1L);
        assertThat(suggestion.categoryCode()).isEqualTo("plumbing");
        assertThat(suggestion.confidence()).isEqualTo(0.96);
        assertThat(suggestion.questions()).isEmpty();
    }

    @Test
    void classify_clearDescriptionWithoutImage_returnsClassified() {
        when(categoryRepository.findAll()).thenReturn(List.of(plumbing, generalHandyman));
        when(aiClassificationClient.classify(anyString(), anyList())).thenReturn(
                new ClassificationResult(ClassificationStatus.CLASSIFIED, "plumbing", 0.9,
                        "Description alone is clear.", List.of()));

        ClassificationSuggestion suggestion = classificationService.classify("description", List.of());

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.categoryCode()).isEqualTo("plumbing");
    }

    @Test
    void classify_contradictionBetweenTextAndImage_returnsQuestionsWithoutCategoryResolution() {
        List<ClarificationQuestion> questions = List.of(
                new ClarificationQuestion("q1", "Where does the water come from?",
                        List.of("From the air conditioner", "From a pipe or wall", "I am not sure")));

        when(aiClassificationClient.classify(anyString(), anyList())).thenReturn(
                new ClassificationResult(ClassificationStatus.QUESTIONS, null, 0.6,
                        "Description suggests AC, image suggests plumbing.", questions));

        ClassificationSuggestion suggestion = classificationService.classify(
                "The air conditioner is leaking water.", List.of("key.jpg"));

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.QUESTIONS);
        assertThat(suggestion.categoryId()).isNull();
        assertThat(suggestion.categoryCode()).isNull();
        assertThat(suggestion.questions()).hasSize(1);
        // categoryRepository must never be consulted when there's no categoryCode to resolve.
        Mockito.verifyNoInteractions(categoryRepository);
    }

    @Test
    void classify_ambiguousEvidence_returnsQuestionsWithAtMostThreeQuestions() {
        List<ClarificationQuestion> questions = List.of(
                new ClarificationQuestion("q1", "Question 1", List.of("A", "B")),
                new ClarificationQuestion("q2", "Question 2", List.of("A", "B")),
                new ClarificationQuestion("q3", "Question 3", List.of("A", "B")));

        when(aiClassificationClient.classify(anyString(), anyList())).thenReturn(
                new ClassificationResult(ClassificationStatus.QUESTIONS, null, 0.55,
                        "Two categories are realistically possible.", questions));

        ClassificationSuggestion suggestion = classificationService.classify("ambiguous description", List.of());

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.QUESTIONS);
        assertThat(suggestion.questions()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void classifyWithClarification_afterAnswers_returnsFinalClassified() {
        when(categoryRepository.findAll()).thenReturn(List.of(plumbing, generalHandyman));
        List<ClarificationAnswer> answers = List.of(
                new ClarificationAnswer("Where does the water come from?", "Directly from the air conditioner"));
        when(aiClassificationClient.classifyWithClarification(anyString(), anyList(), Mockito.eq(answers)))
                .thenReturn(new ClassificationResult(ClassificationStatus.CLASSIFIED, "plumbing", 0.93,
                        "Confirmed plumbing leak from clarification answers.", List.of()));

        ClassificationSuggestion suggestion = classificationService.classifyWithClarification(
                "The air conditioner is leaking water.", List.of("key.jpg"), answers);

        assertThat(suggestion.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(suggestion.categoryCode()).isEqualTo("plumbing");
        assertThat(suggestion.confidence()).isEqualTo(0.93);
        assertThat(suggestion.questions()).isEmpty();
    }

    @Test
    void classify_unrecognizedCategoryCode_fallsBackToGeneralHandyman() {
        when(categoryRepository.findAll()).thenReturn(List.of(plumbing, generalHandyman));
        when(aiClassificationClient.classify(anyString(), anyList())).thenReturn(
                new ClassificationResult(ClassificationStatus.CLASSIFIED, "not_a_real_category", 0.8,
                        "explanation", List.of()));

        ClassificationSuggestion suggestion = classificationService.classify("description", List.of());

        assertThat(suggestion.categoryCode()).isEqualTo("general_handyman");
        assertThat(suggestion.confidence()).isNull();
    }

    @Test
    void classify_emptyCategoryDatabase_failsSafelyAndExplicitly() {
        when(categoryRepository.findAll()).thenReturn(List.of());
        when(aiClassificationClient.classify(anyString(), anyList())).thenReturn(
                new ClassificationResult(ClassificationStatus.CLASSIFIED, "plumbing", 0.8, "explanation", List.of()));

        assertThatThrownBy(() -> classificationService.classify("description", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void classify_confidenceIsPassedThroughUnchanged_withinValidRange() {
        when(categoryRepository.findAll()).thenReturn(List.of(plumbing, generalHandyman));
        when(aiClassificationClient.classify(anyString(), anyList())).thenReturn(
                new ClassificationResult(ClassificationStatus.CLASSIFIED, "plumbing", 0.42, "explanation", List.of()));

        ClassificationSuggestion suggestion = classificationService.classify("description", List.of());

        assertThat(suggestion.confidence()).isBetween(0.0, 1.0);
        assertThat(suggestion.confidence()).isEqualTo(0.42);
    }
}
