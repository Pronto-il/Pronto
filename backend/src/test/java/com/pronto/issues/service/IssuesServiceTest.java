package com.pronto.issues.service;

import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.service.ClassificationService;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.issues.dto.ClarificationAnswerRequest;
import com.pronto.issues.dto.ClassifyRequest;
import com.pronto.issues.dto.ClassifyResponse;
import com.pronto.issues.dto.CreateIssueRequest;
import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueBrief;
import com.pronto.issues.entity.IssueBriefStatus;
import com.pronto.issues.entity.IssueClarification;
import com.pronto.issues.entity.IssueClassification;
import com.pronto.issues.entity.IssueUrgencyType;
import com.pronto.issues.event.IssueCreatedEvent;
import com.pronto.issues.repository.IssueBriefRepository;
import com.pronto.issues.repository.IssueClarificationRepository;
import com.pronto.issues.repository.IssueClassificationRepository;
import com.pronto.issues.repository.IssueImageRepository;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.service.StorageService;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Wiring for the two customer-facing issue endpoints.
 *
 * <p>There is no longer an "initial pass versus clarification round" branch to test — that
 * distinction is gone by design, and what replaces it is worth pinning down instead: the whole
 * accumulated conversation is forwarded on every call, the customer's category hint is passed
 * through, internal diagnostics stay off the wire, and issue creation persists the
 * conversation and seeds the AI-artefact rows before publishing the brief-generation event.
 */
class IssuesServiceTest {

    private ClassificationService classificationService;
    private IssueRepository issueRepository;
    private IssueClarificationRepository clarificationRepository;
    private IssueClassificationRepository classificationRepository;
    private IssueBriefRepository briefRepository;
    private CategoryRepository categoryRepository;
    private ApplicationEventPublisher eventPublisher;
    private IssueImageRepository issueImageRepository;
    private ProfessionalRepository professionalRepository;
    private OrderRepository orderRepository;
    private IssuesService issuesService;

    @BeforeEach
    void setUp() {
        classificationService = Mockito.mock(ClassificationService.class);
        issueRepository = Mockito.mock(IssueRepository.class);
        clarificationRepository = Mockito.mock(IssueClarificationRepository.class);
        classificationRepository = Mockito.mock(IssueClassificationRepository.class);
        briefRepository = Mockito.mock(IssueBriefRepository.class);
        categoryRepository = Mockito.mock(CategoryRepository.class);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        issueImageRepository = Mockito.mock(IssueImageRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        orderRepository = Mockito.mock(OrderRepository.class);

        issuesService = new IssuesService(
                issueRepository,
                issueImageRepository,
                clarificationRepository,
                classificationRepository,
                briefRepository,
                categoryRepository,
                Mockito.mock(StorageClient.class),
                Mockito.mock(StorageService.class),
                classificationService,
                professionalRepository,
                orderRepository,
                Mockito.mock(UserRepository.class),
                eventPublisher);
    }

    private ClassificationSuggestion classified() {
        return new ClassificationSuggestion(ClassificationStatus.CLASSIFIED, 3L, "ac_hvac", 0.94, false, false,
                null, List.of(new CategoryCandidate("ac_hvac", 0.94)), List.of());
    }

    private ClassificationSuggestion asking() {
        return new ClassificationSuggestion(ClassificationStatus.QUESTIONS, null, null, 0.5, false, false,
                "Leak source unclear.",
                List.of(new CategoryCandidate("plumbing", 0.5), new CategoryCandidate("ac_hvac", 0.45)),
                List.of(new ClarificationQuestion("q1", "מאיפה מגיעים המים?",
                        List.of("מהמזגן", "מצינור", "אני לא בטוח/ה"), List.of("ac_hvac", "plumbing"))));
    }

    // -- classify ----------------------------------------------------------------------------

    @Test
    void classifyForwardsTheDescriptionHintAndWholeConversation() {
        when(classificationService.classify(anyString(), anyList(), any(), anyList())).thenReturn(classified());

        List<ClarificationAnswerRequest> answers = List.of(
                new ClarificationAnswerRequest("מאיפה המים?", "מהמזגן"),
                new ClarificationAnswerRequest("מתי זה קורה?", "רק כשהמזגן פועל"));

        issuesService.classify(42L, new ClassifyRequest("המזגן מטפטף מים", List.of(), 2L, answers));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ClarificationExchange>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(classificationService)
                .classify(eq("המזגן מטפטף מים"), eq(List.of()), eq(2L), captor.capture());

        assertThat(captor.getValue()).containsExactly(
                new ClarificationExchange("מאיפה המים?", "מהמזגן"),
                new ClarificationExchange("מתי זה קורה?", "רק כשהמזגן פועל"));
    }

    @Test
    void classifyWithNoAnswersForwardsAnEmptyConversationNotNull() {
        when(classificationService.classify(anyString(), anyList(), any(), anyList())).thenReturn(classified());

        issuesService.classify(42L, new ClassifyRequest("המזגן מטפטף מים", List.of(), null, null));

        Mockito.verify(classificationService).classify("המזגן מטפטף מים", List.of(), null, List.of());
    }

    @Test
    void aClassifiedResultCarriesTheResolvedCategoryAndNoQuestions() {
        when(classificationService.classify(anyString(), anyList(), any(), anyList())).thenReturn(classified());

        ClassifyResponse response =
                issuesService.classify(42L, new ClassifyRequest("המזגן מטפטף מים", List.of(), null, null));

        assertThat(response.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(response.suggestedCategoryId()).isEqualTo(3L);
        assertThat(response.suggestedCategoryCode()).isEqualTo("ac_hvac");
        assertThat(response.questions()).isEmpty();
    }

    @Test
    void aQuestionsResultExposesTheQuestionButNoInternalDiagnostics() {
        when(classificationService.classify(anyString(), anyList(), any(), anyList())).thenReturn(asking());

        ClassifyResponse response =
                issuesService.classify(42L, new ClassifyRequest("יש מים על הרצפה", List.of(), null, null));

        assertThat(response.status()).isEqualTo(ClassificationStatus.QUESTIONS);
        assertThat(response.suggestedCategoryId()).isNull();
        assertThat(response.questions()).hasSize(1);
        assertThat(response.questions().get(0).question()).isEqualTo("מאיפה מגיעים המים?");
        assertThat(response.questions().get(0).options()).hasSize(3);

        // The wire shape itself is the guarantee: candidates, confidence, ambiguity reason and
        // distinguishesBetween have no field to travel in.
        assertThat(ClassifyResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("status", "suggestedCategoryId", "suggestedCategoryCode", "questions");
    }

    // -- create ------------------------------------------------------------------------------

    @Test
    void creatingAnIssuePersistsTheConversationAndSeedsTheAiArtefactRows() {
        when(categoryRepository.existsById(anyLong())).thenReturn(true);
        Issue saved = Mockito.mock(Issue.class);
        when(saved.getId()).thenReturn(77L);
        when(saved.getCategoryId()).thenReturn(3L);
        when(issueRepository.save(any())).thenReturn(saved);

        issuesService.create(42L, new CreateIssueRequest(3L, "המזגן מטפטף מים", IssueUrgencyType.STANDARD,
                List.of(), List.of(
                new ClarificationAnswerRequest("מאיפה המים?", "מהמזגן"),
                new ClarificationAnswerRequest("מתי זה קורה?", "רק כשהמזגן פועל"))));

        ArgumentCaptor<IssueClarification> clarifications = ArgumentCaptor.forClass(IssueClarification.class);
        Mockito.verify(clarificationRepository, Mockito.times(2)).save(clarifications.capture());

        assertThat(clarifications.getAllValues()).extracting(IssueClarification::getPosition)
                .containsExactly((short) 0, (short) 1);
        assertThat(clarifications.getAllValues()).extracting(IssueClarification::getQuestion)
                .containsExactly("מאיפה המים?", "מתי זה קורה?");
        assertThat(clarifications.getAllValues()).allSatisfy(row ->
                assertThat(row.getIssueId()).isEqualTo(77L));

        ArgumentCaptor<IssueClassification> classification = ArgumentCaptor.forClass(IssueClassification.class);
        Mockito.verify(classificationRepository).save(classification.capture());
        assertThat(classification.getValue().getClarificationRounds()).isEqualTo((short) 2);

        ArgumentCaptor<IssueBrief> brief = ArgumentCaptor.forClass(IssueBrief.class);
        Mockito.verify(briefRepository).save(brief.capture());
        assertThat(brief.getValue().getStatus()).isEqualTo(IssueBriefStatus.PENDING);

        Mockito.verify(eventPublisher).publishEvent(new IssueCreatedEvent(77L));
    }

    // -- getById: the brief is asynchronous, so it is routinely absent ------------------------

    /**
     * Case E. A professional can accept and open a job seconds after it is created, well before
     * the background brief finishes — or after it failed, or on an issue predating the feature.
     * All three look identical here, and none of them may break the endpoint.
     */
    @Test
    void aProfessionalCanLoadAnIssueWhoseBriefDoesNotExistYet() {
        professionalViewableIssue();
        when(briefRepository.findById(77L)).thenReturn(java.util.Optional.empty());

        var response = issuesService.getById(500L, "PROFESSIONAL", 77L);

        assertThat(response.prontoAnalysis()).isNull();
        // Everything the professional had before briefs existed is still there.
        assertThat(response.description()).isEqualTo("יש נזילה מתחת לכיור");
        assertThat(response.categoryId()).isEqualTo(1L);
        assertThat(response.clarifications()).isEmpty();
    }

    @Test
    void aPendingBriefIsReturnedWithItsStatusRatherThanOmitted() {
        professionalViewableIssue();
        when(briefRepository.findById(77L)).thenReturn(java.util.Optional.of(new IssueBrief(77L)));

        var response = issuesService.getById(500L, "PROFESSIONAL", 77L);

        // PENDING and FAILED are different states to a client — "not ready yet" is not the same
        // as "we tried and could not" — so the object is returned and the status carries it.
        assertThat(response.prontoAnalysis()).isNotNull();
        assertThat(response.prontoAnalysis().status()).isEqualTo(IssueBriefStatus.PENDING);
        assertThat(response.prontoAnalysis().likelyIssue()).isNull();
        assertThat(response.prontoAnalysis().recommendedTools()).isEmpty();
    }

    @Test
    void aFailedBriefLeavesTheIssueFullyUsable() {
        // Case F, from the reader's side: generation failed permanently, and the job screen must
        // still work off the customer's own report.
        professionalViewableIssue();
        IssueBrief failed = new IssueBrief(77L);
        failed.markFailed();
        // Both built before their stubbing calls — `clarificationRow` stubs its own mock, and
        // doing that inside a when(...) argument leaves Mockito mid-stub.
        List<IssueClarification> rows = List.of(clarificationRow("מאיפה המים?", "מהסיפון"));
        when(briefRepository.findById(77L)).thenReturn(java.util.Optional.of(failed));
        when(clarificationRepository.findByIssueIdOrderByPositionAsc(77L)).thenReturn(rows);

        var response = issuesService.getById(500L, "PROFESSIONAL", 77L);

        assertThat(response.prontoAnalysis().status()).isEqualTo(IssueBriefStatus.FAILED);
        assertThat(response.description()).isEqualTo("יש נזילה מתחת לכיור");
        assertThat(response.clarifications()).hasSize(1);
        assertThat(response.clarifications().get(0).answer()).isEqualTo("מהסיפון");
    }

    @Test
    void aCustomerNeverReceivesTheProfessionalBrief() {
        Issue issue = Mockito.mock(Issue.class);
        when(issue.getId()).thenReturn(77L);
        when(issue.getCustomerId()).thenReturn(42L);
        when(issue.getCategoryId()).thenReturn(1L);
        when(issue.getDescription()).thenReturn("יש נזילה מתחת לכיור");
        when(issueRepository.findById(77L)).thenReturn(java.util.Optional.of(issue));
        when(issueImageRepository.findByIssueId(77L)).thenReturn(List.of());
        when(clarificationRepository.findByIssueIdOrderByPositionAsc(77L)).thenReturn(List.of());

        var response = issuesService.getById(42L, "CUSTOMER", 77L);

        assertThat(response.prontoAnalysis()).isNull();
        // Resolved only on the professional branch — the customer path never even reads it.
        Mockito.verifyNoInteractions(briefRepository);
    }

    private void professionalViewableIssue() {
        Issue issue = Mockito.mock(Issue.class);
        when(issue.getId()).thenReturn(77L);
        when(issue.getCustomerId()).thenReturn(42L);
        when(issue.getCategoryId()).thenReturn(1L);
        when(issue.getDescription()).thenReturn("יש נזילה מתחת לכיור");
        when(issueRepository.findById(77L)).thenReturn(java.util.Optional.of(issue));

        com.pronto.professionals.entity.Professional professional =
                Mockito.mock(com.pronto.professionals.entity.Professional.class);
        when(professional.getId()).thenReturn(9L);
        when(professionalRepository.findByUserId(500L)).thenReturn(java.util.Optional.of(professional));
        when(orderRepository.existsByIssueIdAndProfessionalId(77L, 9L)).thenReturn(true);

        when(issueImageRepository.findByIssueId(77L)).thenReturn(List.of());
        when(clarificationRepository.findByIssueIdOrderByPositionAsc(77L)).thenReturn(List.of());
    }

    private IssueClarification clarificationRow(String question, String answer) {
        IssueClarification row = Mockito.mock(IssueClarification.class);
        when(row.getQuestion()).thenReturn(question);
        when(row.getAnswer()).thenReturn(answer);
        return row;
    }

    @Test
    void creatingAnIssueWithNoClarificationStillSeedsTheArtefactRows() {
        when(categoryRepository.existsById(anyLong())).thenReturn(true);
        Issue saved = Mockito.mock(Issue.class);
        when(saved.getId()).thenReturn(78L);
        when(issueRepository.save(any())).thenReturn(saved);

        issuesService.create(42L, new CreateIssueRequest(1L, "הכיור סתום", IssueUrgencyType.STANDARD,
                List.of(), null));

        Mockito.verifyNoInteractions(clarificationRepository);
        Mockito.verify(classificationRepository).save(any());
        Mockito.verify(briefRepository).save(any());
        Mockito.verify(eventPublisher).publishEvent(new IssueCreatedEvent(78L));
    }
}
