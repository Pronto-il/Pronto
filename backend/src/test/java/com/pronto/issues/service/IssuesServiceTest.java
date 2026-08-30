package com.pronto.issues.service;

import com.pronto.users.service.ContactVerificationGuard;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.service.ClassificationService;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.dto.ClarificationAnswerRequest;
import com.pronto.issues.dto.ClassifyRequest;
import com.pronto.issues.dto.ClassifyResponse;
import com.pronto.issues.dto.CreateIssueRequest;
import com.pronto.issues.dto.UpdateIssueCategoryRequest;
import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueBrief;
import com.pronto.issues.entity.IssueBriefStatus;
import com.pronto.issues.entity.IssueClarification;
import com.pronto.issues.entity.IssueClassification;
import com.pronto.issues.entity.IssueStatus;
import com.pronto.issues.entity.IssueUrgencyType;
import com.pronto.issues.event.IssueCategoryChangedEvent;
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
import static org.assertj.core.api.InstanceOfAssertFactories.list;
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
    private ContactVerificationGuard contactVerificationGuard;

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

        contactVerificationGuard = Mockito.mock(ContactVerificationGuard.class);

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
                contactVerificationGuard,
                eventPublisher);
    }

    private ClassificationSuggestion classified() {
        return new ClassificationSuggestion(ClassificationStatus.CLASSIFIED, "טכנאי מזגנים", 3L, "ac_hvac",
                0.94, false, false, null, List.of(new CategoryCandidate("ac_hvac", 0.94)), List.of());
    }

    private ClassificationSuggestion asking() {
        return new ClassificationSuggestion(ClassificationStatus.QUESTIONS, "אינסטלטור", null, null, 0.5,
                false, false, "Leak source unclear.",
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
        //
        // `detectedProfession` is the one deliberate diagnostic-shaped exception, and it is not a
        // diagnostic: it is the Hebrew trade name the unsupported-profession screen renders,
        // without which "we do not cover that" names nothing.
        //
        // classification-v6 added the four structured classification fields. They are the same
        // answer `detectedProfession` already carried, in codes a client can branch on instead of
        // string-matching Hebrew -- not new information about the model's reasoning. Asserted
        // exactly, so the next field cannot arrive without someone deciding to add it here.
        assertThat(ClassifyResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("status", "detectedProfession", "professionCode", "subcategoryCode",
                        "intent", "urgency", "suggestedCategoryId", "suggestedCategoryCode", "questions");

        // Stated as its own assertion rather than left implicit in the list above: these four are
        // the ones that must never appear, and naming them means a future widening of the response
        // fails on the rule it broke instead of on an ordering mismatch.
        assertThat(ClassifyResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("confidence", "candidates", "ambiguityReason", "explanation");
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

    // -- updateCategory ----------------------------------------------------------------------

    /**
     * Builds an issue the customer 42 owns, sitting in the state this endpoint is for.
     */
    private Issue editableIssue(Long categoryId) {
        Issue issue = Mockito.mock(Issue.class);
        when(issue.getId()).thenReturn(77L);
        when(issue.getCustomerId()).thenReturn(42L);
        when(issue.getCategoryId()).thenReturn(categoryId);
        Mockito.lenient().when(issue.getStatus()).thenReturn(IssueStatus.OPEN);
        Mockito.lenient().when(issue.getDescription()).thenReturn("water leaking under the sink");
        Mockito.lenient().when(issue.getUrgencyType()).thenReturn(IssueUrgencyType.STANDARD);
        return issue;
    }

    @Test
    void reConfirmingTheSameCategoryChangesNothingAtAll() {
        // The common path: the customer walked back to the classification screen, agreed with it
        // and continued. No write, no brief regeneration -- and the same issue comes back.
        Issue issue = editableIssue(1L);
        when(issueRepository.findById(77L)).thenReturn(java.util.Optional.of(issue));
        when(categoryRepository.existsById(1L)).thenReturn(true);
        when(issueImageRepository.findByIssueId(77L)).thenReturn(List.of());

        var response = issuesService.updateCategory(42L, 77L, new UpdateIssueCategoryRequest(1L));

        assertThat(response.id()).isEqualTo(77L);
        assertThat(response.categoryId()).isEqualTo(1L);
        Mockito.verify(issueRepository, Mockito.never()).updateCategoryIfOpen(anyLong(), anyLong(), any());
        Mockito.verify(issueRepository, Mockito.never()).save(any());
        Mockito.verifyNoInteractions(eventPublisher);
    }

    @Test
    void changingTheCategoryUpdatesTheSameIssueAndNeverCreatesASecondOne() {
        // The bug this endpoint exists for: correcting the category used to POST a brand new issue
        // and leave the original behind as an OPEN orphan.
        Issue before = editableIssue(1L);
        Issue after = editableIssue(2L);
        when(issueRepository.findById(77L))
                .thenReturn(java.util.Optional.of(before), java.util.Optional.of(after));
        when(categoryRepository.existsById(2L)).thenReturn(true);
        when(issueRepository.updateCategoryIfOpen(eq(77L), eq(2L), any())).thenReturn(1);
        when(issueImageRepository.findByIssueId(77L)).thenReturn(List.of());

        var response = issuesService.updateCategory(42L, 77L, new UpdateIssueCategoryRequest(2L));

        assertThat(response.id()).isEqualTo(77L);
        assertThat(response.categoryId()).isEqualTo(2L);
        Mockito.verify(issueRepository).updateCategoryIfOpen(eq(77L), eq(2L), any());
        // No second issue, by either route into existence.
        Mockito.verify(issueRepository, Mockito.never()).save(any());
        Mockito.verify(eventPublisher, Mockito.never()).publishEvent(any(IssueCreatedEvent.class));
        // The brief is written for a named trade, so it has to be regenerated for the new one.
        Mockito.verify(eventPublisher).publishEvent(new IssueCategoryChangedEvent(77L));
    }

    @Test
    void changingTheCategoryLeavesTheRestOfTheIssueAlone() {
        // The endpoint takes a category and nothing else, and the update touches one column, so
        // the description/photos/answers cannot be disturbed by this call.
        // Both mocks are built before any stubbing call starts — building one inside a `when(...)`
        // argument list is nested stubbing, which Mockito rejects.
        Issue before = editableIssue(1L);
        Issue after = editableIssue(2L);
        when(issueRepository.findById(77L))
                .thenReturn(java.util.Optional.of(before), java.util.Optional.of(after));
        when(categoryRepository.existsById(2L)).thenReturn(true);
        when(issueRepository.updateCategoryIfOpen(eq(77L), eq(2L), any())).thenReturn(1);
        when(issueImageRepository.findByIssueId(77L)).thenReturn(List.of());

        issuesService.updateCategory(42L, 77L, new UpdateIssueCategoryRequest(2L));

        Mockito.verifyNoInteractions(clarificationRepository);
        Mockito.verify(issueImageRepository, Mockito.never()).save(any());
        Mockito.verify(issueImageRepository, Mockito.never()).deleteAll(any());
    }

    @Test
    void aCustomerCannotChangeSomebodyElsesIssue() {
        Issue issue = editableIssue(1L);
        when(issueRepository.findById(77L)).thenReturn(java.util.Optional.of(issue));

        ApiException thrown = catchApiException(
                () -> issuesService.updateCategory(999L, 77L, new UpdateIssueCategoryRequest(2L)));

        assertThat(thrown.getCode()).isEqualTo(ErrorCode.FORBIDDEN);
        Mockito.verify(issueRepository, Mockito.never()).updateCategoryIfOpen(anyLong(), anyLong(), any());
        Mockito.verifyNoInteractions(eventPublisher);
    }

    @Test
    void anIssueThatIsNoLongerOpenCannotBeChanged() {
        // Already dispatched: somebody is preparing for the trade this issue currently names.
        Issue issue = editableIssue(1L);
        when(issue.getStatus()).thenReturn(IssueStatus.BOOKED);
        when(issueRepository.findById(77L)).thenReturn(java.util.Optional.of(issue));
        when(categoryRepository.existsById(2L)).thenReturn(true);

        ApiException thrown = catchApiException(
                () -> issuesService.updateCategory(42L, 77L, new UpdateIssueCategoryRequest(2L)));

        assertThat(thrown.getCode()).isEqualTo(ErrorCode.ISSUE_NOT_EDITABLE);
        Mockito.verify(issueRepository, Mockito.never()).updateCategoryIfOpen(anyLong(), anyLong(), any());
        Mockito.verifyNoInteractions(eventPublisher);
    }

    @Test
    void aBookingThatLandsMidRequestLosesTheUpdateRatherThanOverwritingIt() {
        // The read said OPEN, the guarded UPDATE disagreed -- that is the race the WHERE clause
        // exists for, and it is reported, not ignored.
        Issue issue = editableIssue(1L);
        when(issueRepository.findById(77L)).thenReturn(java.util.Optional.of(issue));
        when(categoryRepository.existsById(2L)).thenReturn(true);
        when(issueRepository.updateCategoryIfOpen(eq(77L), eq(2L), any())).thenReturn(0);

        ApiException thrown = catchApiException(
                () -> issuesService.updateCategory(42L, 77L, new UpdateIssueCategoryRequest(2L)));

        assertThat(thrown.getCode()).isEqualTo(ErrorCode.ISSUE_NOT_EDITABLE);
        Mockito.verifyNoInteractions(eventPublisher);
    }

    @Test
    void aCategoryThatDoesNotExistIsRejected() {
        Issue issue = editableIssue(1L);
        when(issueRepository.findById(77L)).thenReturn(java.util.Optional.of(issue));
        when(categoryRepository.existsById(4242L)).thenReturn(false);

        ApiException thrown = catchApiException(
                () -> issuesService.updateCategory(42L, 77L, new UpdateIssueCategoryRequest(4242L)));

        assertThat(thrown.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(thrown.getDetails()).asInstanceOf(list(FieldError.class))
                .extracting(FieldError::field).containsExactly("categoryId");
        Mockito.verify(issueRepository, Mockito.never()).updateCategoryIfOpen(anyLong(), anyLong(), any());
        Mockito.verifyNoInteractions(eventPublisher);
    }

    @Test
    void anIssueThatDoesNotExistIsANotFound() {
        when(issueRepository.findById(77L)).thenReturn(java.util.Optional.empty());

        ApiException thrown = catchApiException(
                () -> issuesService.updateCategory(42L, 77L, new UpdateIssueCategoryRequest(2L)));

        assertThat(thrown.getCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    private static ApiException catchApiException(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected an ApiException");
    }

    // ---- Production MS1 pre-DONE audit: the phone gate on classification ----

    @Test
    void classify_requiresVerifiedContactChannels() {
        // POST /api/issues was gated from the start; POST /api/issues/classify was not, which left an
        // unverified account able to spend OpenAI requests indefinitely. The route is authenticated
        // and CUSTOMER-only, so there is no anonymous classification flow this can break.
        Mockito.doThrow(new ApiException(ErrorCode.PHONE_VERIFICATION_REQUIRED,
                        "Verify your phone number before continuing."))
                .when(contactVerificationGuard).requireVerifiedContactChannels(42L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> issuesService.classify(42L,
                new ClassifyRequest("המזגן מטפטף מים", List.of(), null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.PHONE_VERIFICATION_REQUIRED));

        Mockito.verifyNoInteractions(classificationService);
    }

    @Test
    void classify_proceedsForAVerifiedAccount() {
        // The default mock guard does nothing, i.e. the account is verified — so the call reaches the
        // classifier instead of being turned away. (The classifier itself is stubbed by the existing
        // fixtures in this class; what matters here is only that the gate let the call through.)
        try {
            issuesService.classify(42L, new ClassifyRequest("המזגן מטפטף מים", List.of(), null, null));
        } catch (RuntimeException ignored) {
            // Downstream stubbing, not the gate. The verifications below are the assertion.
        }

        Mockito.verify(contactVerificationGuard).requireVerifiedContactChannels(42L);
        Mockito.verify(classificationService)
                .classify(Mockito.eq("המזגן מטפטף מים"), Mockito.anyList(), Mockito.isNull(), Mockito.anyList());
    }
}
