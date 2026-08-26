package com.pronto.issues.service;

import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ImageAttachment;
import com.pronto.ai.dto.LikelyIssue;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.prompt.ClassificationPromptBuilder;
import com.pronto.ai.service.ClassificationService;
import com.pronto.ai.service.IssueImageResolver;
import com.pronto.ai.service.ProfessionalBriefService;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueBrief;
import com.pronto.issues.entity.IssueBriefStatus;
import com.pronto.issues.entity.IssueClassification;
import com.pronto.issues.entity.IssueImage;
import com.pronto.issues.entity.IssueUrgencyType;
import com.pronto.issues.repository.IssueBriefRepository;
import com.pronto.issues.repository.IssueClarificationRepository;
import com.pronto.issues.repository.IssueClassificationRepository;
import com.pronto.issues.repository.IssueImageRepository;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.storage.client.StorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The asynchronous brief job's failure and cost behaviour.
 *
 * <p>Case F from the hardening review lives here: a brief that cannot be generated must be
 * recorded as {@code FAILED} and then left alone — never retried into a loop, and never
 * allowed to affect the issue. Also pins the two things a config flag flip could silently
 * undo: telemetry is off unless asked for, and the images are resolved once per run rather
 * than once per model call.
 */
class IssueBriefServiceTest {

    private static final Long ISSUE_ID = 77L;
    private static final Long CATEGORY_ID = 1L;

    private IssueRepository issueRepository;
    private IssueImageRepository issueImageRepository;
    private IssueClarificationRepository clarificationRepository;
    private IssueClassificationRepository classificationRepository;
    private IssueBriefRepository briefRepository;
    private ProfessionalBriefService professionalBriefService;
    private ClassificationService classificationService;
    private IssueImageResolver imageResolver;

    @BeforeEach
    void setUp() {
        issueRepository = Mockito.mock(IssueRepository.class);
        issueImageRepository = Mockito.mock(IssueImageRepository.class);
        clarificationRepository = Mockito.mock(IssueClarificationRepository.class);
        classificationRepository = Mockito.mock(IssueClassificationRepository.class);
        briefRepository = Mockito.mock(IssueBriefRepository.class);
        professionalBriefService = Mockito.mock(ProfessionalBriefService.class);
        classificationService = Mockito.mock(ClassificationService.class);
        imageResolver = Mockito.mock(IssueImageResolver.class);

        Issue issue = Mockito.mock(Issue.class);
        when(issue.getId()).thenReturn(ISSUE_ID);
        when(issue.getCategoryId()).thenReturn(CATEGORY_ID);
        when(issue.getDescription()).thenReturn("יש נזילה מתחת לכיור");
        when(issue.getUrgencyType()).thenReturn(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));

        IssueImage image = Mockito.mock(IssueImage.class);
        when(image.getImageKey()).thenReturn("customers/42/photo.jpg");
        when(issueImageRepository.findByIssueId(ISSUE_ID)).thenReturn(List.of(image));
        when(clarificationRepository.findByIssueIdOrderByPositionAsc(ISSUE_ID)).thenReturn(List.of());
        when(briefRepository.findById(ISSUE_ID)).thenReturn(Optional.of(new IssueBrief(ISSUE_ID)));
        when(imageResolver.resolveBestEffort(anyList()))
                .thenReturn(List.of(ImageAttachment.of("customers/42/photo.jpg", new byte[] {1, 2, 3},
                        "image/jpeg")));
    }

    private IssueBriefService service(boolean recordFinalClassification) {
        return new IssueBriefService(issueRepository, issueImageRepository, clarificationRepository,
                classificationRepository, briefRepository, professionalBriefService, classificationService,
                imageResolver, recordFinalClassification, "gpt-4o-mini");
    }

    private ProfessionalBriefResponse someBrief() {
        return new ProfessionalBriefResponse("סיכום", null, List.of(),
                new LikelyIssue("נזילה בסיפון", 0.8, List.of("ראיה")), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void aSuccessfulRunStoresTheBriefAsReady() {
        when(professionalBriefService.generateFromResolved(anyString(), anyList(), anyLong(), any(), anyList()))
                .thenReturn(someBrief());

        service(false).generateFor(ISSUE_ID);

        ArgumentCaptor<IssueBrief> saved = ArgumentCaptor.forClass(IssueBrief.class);
        Mockito.verify(briefRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(IssueBriefStatus.READY);
        assertThat(saved.getValue().getLikelyIssueDescription()).isEqualTo("נזילה בסיפון");
    }

    /** Case F. */
    @Test
    void aFailedBriefIsRecordedAsFailedAndNotRetried() {
        when(professionalBriefService.generateFromResolved(anyString(), anyList(), anyLong(), any(), anyList()))
                .thenThrow(new ApiException(ErrorCode.AI_SERVICE_ERROR, "OpenAI is down"));

        service(false).generateFor(ISSUE_ID);

        ArgumentCaptor<IssueBrief> saved = ArgumentCaptor.forClass(IssueBrief.class);
        Mockito.verify(briefRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(IssueBriefStatus.FAILED);

        // One attempt, no retry loop around a paid model call.
        Mockito.verify(professionalBriefService, Mockito.times(1))
                .generateFromResolved(anyString(), anyList(), anyLong(), any(), anyList());
    }

    @Test
    void anUnexpectedExceptionAlsoFailsClosedRatherThanEscaping() {
        // The listener runs after commit on a pool thread; letting anything escape would only
        // produce an untraceable stack trace, not a better outcome.
        when(professionalBriefService.generateFromResolved(anyString(), anyList(), anyLong(), any(), anyList()))
                .thenThrow(new IllegalStateException("boom"));

        service(false).generateFor(ISSUE_ID);

        ArgumentCaptor<IssueBrief> saved = ArgumentCaptor.forClass(IssueBrief.class);
        Mockito.verify(briefRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(IssueBriefStatus.FAILED);
    }

    @Test
    void aMissingIssueIsSkippedWithoutTouchingAnyModel() {
        when(issueRepository.findById(999L)).thenReturn(Optional.empty());

        service(true).generateFor(999L);

        Mockito.verifyNoInteractions(professionalBriefService, classificationService, imageResolver);
    }

    @Test
    void telemetryIsOffUnlessExplicitlyEnabled() {
        when(professionalBriefService.generateFromResolved(anyString(), anyList(), anyLong(), any(), anyList()))
                .thenReturn(someBrief());

        service(false).generateFor(ISSUE_ID);

        Mockito.verifyNoInteractions(classificationService);
        Mockito.verifyNoInteractions(classificationRepository);
    }

    @Test
    void imagesAreResolvedOncePerRunEvenWhenBothModelCallsHappen() {
        when(professionalBriefService.generateFromResolved(anyString(), anyList(), anyLong(), any(), anyList()))
                .thenReturn(someBrief());
        when(classificationService.classifyResolved(anyString(), anyList(), any(), anyList()))
                .thenThrow(new ApiException(ErrorCode.AI_SERVICE_ERROR, "telemetry unavailable"));

        service(true).generateFor(ISSUE_ID);

        // One download+encode shared by both calls, not one per call.
        Mockito.verify(imageResolver, Mockito.times(1)).resolveBestEffort(anyList());
        // A telemetry failure must not stop the brief.
        Mockito.verify(professionalBriefService)
                .generateFromResolved(anyString(), anyList(), anyLong(), any(), anyList());
    }

    /**
     * Without the prompt and model on the row, the stored drift signal is uninterpretable
     * across a prompt bump or a model upgrade — the disagreement rate could move because
     * routing changed or because the thing routing was replaced, and the row could not say
     * which. See {@code V52__alter_issue_classifications_add_prompt_and_model.sql}.
     */
    @Test
    void storedTelemetryRecordsWhichPromptAndModelProducedIt() {
        when(professionalBriefService.generateFromResolved(anyString(), anyList(), anyLong(), any(), anyList()))
                .thenReturn(someBrief());
        when(classificationService.classifyResolved(anyString(), anyList(), any(), anyList()))
                .thenReturn(new ClassificationSuggestion(ClassificationStatus.CLASSIFIED, CATEGORY_ID,
                        "plumbing", 0.91, false, false, null, List.of(), List.of()));
        when(classificationRepository.findById(ISSUE_ID)).thenReturn(Optional.empty());

        service(true).generateFor(ISSUE_ID);

        ArgumentCaptor<IssueClassification> saved = ArgumentCaptor.forClass(IssueClassification.class);
        Mockito.verify(classificationRepository).save(saved.capture());
        assertThat(saved.getValue().getPromptVersion())
                .isEqualTo(ClassificationPromptBuilder.PROMPT_VERSION);
        assertThat(saved.getValue().getModel()).isEqualTo("gpt-4o-mini");
        assertThat(saved.getValue().getAiCategoryCode()).isEqualTo("plumbing");
    }

    @Test
    void theBriefIsBuiltFromTheFullClarificationConversationInOrder() {
        // Built before the stubbing call: `clarification` stubs its own mock, and doing that
        // inside a when(...) argument leaves Mockito mid-stub.
        List<com.pronto.issues.entity.IssueClarification> rows = List.of(
                clarification("מאיפה המים?", "מהסיפון"),
                clarification("מתי זה קורה?", "רק בשימוש"));
        when(clarificationRepository.findByIssueIdOrderByPositionAsc(ISSUE_ID)).thenReturn(rows);
        when(professionalBriefService.generateFromResolved(anyString(), anyList(), anyLong(), any(), anyList()))
                .thenReturn(someBrief());

        service(false).generateFor(ISSUE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ClarificationExchange>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(professionalBriefService)
                .generateFromResolved(anyString(), anyList(), anyLong(), any(), captor.capture());

        assertThat(captor.getValue()).containsExactly(
                new ClarificationExchange("מאיפה המים?", "מהסיפון"),
                new ClarificationExchange("מתי זה קורה?", "רק בשימוש"));
    }

    private com.pronto.issues.entity.IssueClarification clarification(String question, String answer) {
        com.pronto.issues.entity.IssueClarification row =
                Mockito.mock(com.pronto.issues.entity.IssueClarification.class);
        when(row.getQuestion()).thenReturn(question);
        when(row.getAnswer()).thenReturn(answer);
        return row;
    }
}
