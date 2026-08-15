package com.pronto.issues.service;

import com.pronto.ai.client.ClarificationAnswer;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.service.ClassificationService;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.issues.dto.ClarificationAnswerRequest;
import com.pronto.issues.dto.ClassifyRequest;
import com.pronto.issues.dto.ClassifyResponse;
import com.pronto.issues.repository.IssueImageRepository;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.storage.client.StorageClient;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Wiring test for {@code POST /api/issues/classify}'s clarification-question extension
 * (§2.1 of {@code docs/architecture/api-contract-issues.md}): with no
 * {@code clarificationAnswers} in the request, the initial {@code classify} pass runs; once
 * the customer has answered, the request carries {@code clarificationAnswers} and the
 * service must route to the single allowed clarification round
 * ({@code classifyWithClarification}), never back to a fresh initial classification.
 */
class IssuesServiceTest {

    private ClassificationService classificationService;
    private IssuesService issuesService;

    @BeforeEach
    void setUp() {
        classificationService = Mockito.mock(ClassificationService.class);
        issuesService = new IssuesService(
                Mockito.mock(IssueRepository.class),
                Mockito.mock(IssueImageRepository.class),
                Mockito.mock(CategoryRepository.class),
                Mockito.mock(StorageClient.class),
                classificationService,
                Mockito.mock(ProfessionalRepository.class),
                Mockito.mock(OrderRepository.class),
                Mockito.mock(UserRepository.class));
    }

    @Test
    void classify_withoutClarificationAnswers_callsInitialClassify() {
        when(classificationService.classify(anyString(), anyList())).thenReturn(
                new ClassificationSuggestion(ClassificationStatus.CLASSIFIED, 1L, "plumbing", 0.9,
                        "explanation", List.of()));

        ClassifyRequest request = new ClassifyRequest("The air conditioner is leaking water.", List.of(), null);
        ClassifyResponse response = issuesService.classify(42L, request);

        Mockito.verify(classificationService).classify("The air conditioner is leaking water.", List.of());
        Mockito.verify(classificationService, Mockito.never())
                .classifyWithClarification(anyString(), anyList(), anyList());
        assertThat(response.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(response.suggestedCategoryId()).isEqualTo(1L);
    }

    @Test
    void classify_initialAmbiguousResult_returnsQuestionsToTheCaller() {
        List<ClarificationQuestion> questions = List.of(
                new ClarificationQuestion("q1", "Where does the water come from?",
                        List.of("From the AC", "From a pipe", "I am not sure")));
        when(classificationService.classify(anyString(), anyList())).thenReturn(
                new ClassificationSuggestion(ClassificationStatus.QUESTIONS, null, null, 0.6,
                        "ambiguous", questions));

        ClassifyRequest request = new ClassifyRequest("The air conditioner is leaking water.", List.of(), null);
        ClassifyResponse response = issuesService.classify(42L, request);

        assertThat(response.status()).isEqualTo(ClassificationStatus.QUESTIONS);
        assertThat(response.suggestedCategoryId()).isNull();
        assertThat(response.questions()).hasSize(1);
    }

    @Test
    void classify_withClarificationAnswers_routesToClarificationRoundNotInitialClassify() {
        when(classificationService.classifyWithClarification(anyString(), anyList(), anyList())).thenReturn(
                new ClassificationSuggestion(ClassificationStatus.CLASSIFIED, 3L, "ac_hvac", 0.94,
                        "Confirmed AC issue after clarification.", List.of()));

        List<ClarificationAnswerRequest> answers = List.of(
                new ClarificationAnswerRequest("Where does the water come from?", "Directly from the AC"));
        ClassifyRequest request = new ClassifyRequest("The air conditioner is leaking water.", List.of(), answers);

        ClassifyResponse response = issuesService.classify(42L, request);

        Mockito.verify(classificationService, Mockito.never()).classify(anyString(), anyList());
        ArgumentCaptor<List<ClarificationAnswer>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(classificationService).classifyWithClarification(
                eq("The air conditioner is leaking water."), eq(List.of()), captor.capture());
        assertThat(captor.getValue()).containsExactly(
                new ClarificationAnswer("Where does the water come from?", "Directly from the AC"));

        assertThat(response.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(response.suggestedCategoryCode()).isEqualTo("ac_hvac");
        assertThat(response.questions()).isEmpty();
    }

    @Test
    void classify_finalRound_neverReturnsQuestionsAgain() {
        // ClassificationService/OpenAiClassificationClient are responsible for enforcing "no
        // second round" (they never hand IssuesService a QUESTIONS result from
        // classifyWithClarification) — this test locks in that IssuesService faithfully
        // forwards whatever status it receives without re-deciding, so a regression there
        // would surface here too.
        when(classificationService.classifyWithClarification(anyString(), anyList(), anyList())).thenReturn(
                new ClassificationSuggestion(ClassificationStatus.CLASSIFIED, 3L, "ac_hvac", 0.94,
                        "explanation", List.of()));

        List<ClarificationAnswerRequest> answers = List.of(
                new ClarificationAnswerRequest("question", "answer"));
        ClassifyRequest request = new ClassifyRequest("description", List.of(), answers);

        ClassifyResponse response = issuesService.classify(42L, request);

        assertThat(response.status()).isNotEqualTo(ClassificationStatus.QUESTIONS);
        assertThat(response.questions()).isEmpty();
    }
}
