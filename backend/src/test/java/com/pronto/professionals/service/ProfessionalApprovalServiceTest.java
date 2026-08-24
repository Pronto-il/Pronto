package com.pronto.professionals.service;

import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.professionals.dto.ProfessionalApprovalListResponse;
import com.pronto.professionals.dto.ProfessionalReviewDetailResponse;
import com.pronto.professionals.dto.RejectProfessionalRequest;
import com.pronto.professionals.dto.VerificationDocumentUrlResponse;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ProfessionalSubServiceRepository;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MS1's operator surface. Mockito mocks over the repositories, a real {@link StorageService} over a
 * mocked {@link StorageClient} — the same "real collaborator across the client boundary" choice
 * {@code AuthServiceTest} and {@code ProfessionalsServiceTest} already make, so the narrow
 * operator document path is exercised for real rather than stubbed away.
 *
 * <p>Role enforcement is not asserted here on purpose: it lives entirely in
 * {@code ProfessionalsWebConfig}'s {@code RoleRequiredInterceptor} registration, one layer above,
 * and is covered by {@code common.security.AdminRouteGatingTest}. Duplicating a role check into
 * this service is exactly what its Javadoc says not to do.
 */
class ProfessionalApprovalServiceTest {

    /** MS4: `professionals` no longer stores a category or free-text place -- see the entity. */
    private static final long SERVICE_REGION_ID = 4L;
    private static final long BASE_CITY_ID = 40L;
    private static final long CATEGORY_ID = 3L;

    private static final Long PROFESSIONAL_ID = 50L;
    private static final Long PROFESSIONAL_USER_ID = 10L;
    private static final Long OPERATOR_ID = 7L;
    private static final String DOCUMENT_KEY = "verification-documents/10/abc.pdf";

    private ProfessionalRepository professionalRepository;
    private ProfessionalSubServiceRepository professionalSubServiceRepository;
    private UserRepository userRepository;
    private ProfessionalCoverageService professionalCoverageService;
    private StorageClient storageClient;
    private ProfessionalApprovalService service;
    private final AuthenticatedUser operator = new AuthenticatedUser(OPERATOR_ID, UserRole.ADMIN.name());

    @BeforeEach
    void setUp() {
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        professionalSubServiceRepository = Mockito.mock(ProfessionalSubServiceRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        professionalCoverageService = Mockito.mock(ProfessionalCoverageService.class);
        storageClient = Mockito.mock(StorageClient.class);
        StorageService storageService = new StorageService(storageClient, Optional.empty(), 300L);
        service = new ProfessionalApprovalService(professionalRepository, professionalSubServiceRepository,
                userRepository, storageService, professionalCoverageService);
        // MS4: every pre-existing test in this class describes an ordinary, fully-configured
        // professional, so coverage and categories are stubbed to a sane default here; the tests
        // that care override them per-test. ProfessionalCoverageService's own rules are covered by
        // ProfessionalCoverageServiceTest, not by re-asserting them through every consumer.
        Mockito.lenient().when(professionalCoverageService.load(Mockito.any()))
                .thenReturn(new ProfessionalCoverageService.CoverageView(SERVICE_REGION_ID, "גוש דן",
                        BASE_CITY_ID, "תל אביב", List.of(BASE_CITY_ID), List.of("תל אביב"),
                        List.of(CATEGORY_ID)));
        Mockito.lenient().when(professionalCoverageService.categoryIds(Mockito.anyLong()))
                .thenReturn(List.of(CATEGORY_ID));
        Mockito.lenient().when(professionalCoverageService.baseCityName(Mockito.any())).thenReturn("תל אביב");
        Mockito.lenient().when(professionalCoverageService.servesCategory(Mockito.anyLong(), Mockito.anyLong()))
                .thenReturn(true);

        when(userRepository.findById(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(professionalUser()));
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());
        Mockito.lenient().when(professionalRepository.save(any(Professional.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static User professionalUser() {
        User user = new User("Dana Cohen", "dana@example.com", "hash", UserRole.PROFESSIONAL);
        setField(user, "id", PROFESSIONAL_USER_ID);
        return user;
    }

    private Professional pendingProfessional() {
        Professional professional =
                new Professional(PROFESSIONAL_USER_ID, SERVICE_REGION_ID, BASE_CITY_ID, new BigDecimal("250.00"));
        setField(professional, "id", PROFESSIONAL_ID);
        professional.setVerificationDocumentKey(DOCUMENT_KEY);
        return professional;
    }

    private void stubExists(Professional professional) {
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
    }

    // ---- approve / reject ----

    @Test
    void approve_movesPendingToApproved_andRecordsTheOperator() {
        Professional professional = pendingProfessional();
        stubExists(professional);
        when(professionalRepository.hasCompleteOnboarding(PROFESSIONAL_ID)).thenReturn(true);
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(true);

        ProfessionalReviewDetailResponse response = service.approve(operator, PROFESSIONAL_ID);

        assertThat(response.approvalStatus()).isEqualTo(Professional.STATUS_APPROVED);
        assertThat(response.approvalReviewedBy()).isEqualTo(OPERATOR_ID);
        assertThat(response.approvalReviewedAt()).isNotNull();
        assertThat(response.bookable()).isTrue();
        verify(professionalRepository).save(professional);
    }

    @Test
    void approve_withIncompleteOnboarding_succeedsButLeavesThemNonBookable() {
        // D4's core rule, and the case the Playbook singles out: approval is the operator's
        // judgment, completed onboarding is the professional's own. Approving does NOT fabricate
        // the missing sub-services or working hours, so the professional stays invisible until
        // they finish -- and the operator is told so in the response rather than left guessing.
        Professional professional = pendingProfessional();
        stubExists(professional);
        when(professionalRepository.hasCompleteOnboarding(PROFESSIONAL_ID)).thenReturn(false);
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        ProfessionalReviewDetailResponse response = service.approve(operator, PROFESSIONAL_ID);

        assertThat(response.approvalStatus()).isEqualTo(Professional.STATUS_APPROVED);
        assertThat(response.onboardingComplete()).isFalse();
        assertThat(response.bookable()).isFalse();
    }

    @Test
    void reject_storesTheReason_andLeavesThemIneligible() {
        Professional professional = pendingProfessional();
        stubExists(professional);
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        ProfessionalReviewDetailResponse response = service.reject(operator, PROFESSIONAL_ID,
                new RejectProfessionalRequest("  Verification document is illegible.  "));

        assertThat(response.approvalStatus()).isEqualTo(Professional.STATUS_REJECTED);
        assertThat(response.approvalRejectionReason()).isEqualTo("Verification document is illegible.");
        assertThat(response.approvalReviewedBy()).isEqualTo(OPERATOR_ID);
        assertThat(response.bookable()).isFalse();
    }

    @Test
    void approve_twice_secondCallIsRefusedAsAConflict() {
        // The duplicate-request negative case. The second submission must not silently re-stamp
        // the row under a different operator's name.
        Professional professional = pendingProfessional();
        stubExists(professional);
        when(professionalRepository.hasCompleteOnboarding(PROFESSIONAL_ID)).thenReturn(true);
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(true);
        service.approve(operator, PROFESSIONAL_ID);

        AuthenticatedUser otherOperator = new AuthenticatedUser(8L, UserRole.ADMIN.name());
        assertThatThrownBy(() -> service.approve(otherOperator, PROFESSIONAL_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.PROFESSIONAL_APPROVAL_INVALID_TRANSITION));
        assertThat(professional.getApprovalReviewedBy()).isEqualTo(OPERATOR_ID);
    }

    @Test
    void reject_anApprovedProfessional_isRefused_suspensionIsMs7() {
        Professional professional = pendingProfessional();
        stubExists(professional);
        professional.approve(OPERATOR_ID, java.time.Instant.now());

        assertThatThrownBy(() -> service.reject(operator, PROFESSIONAL_ID,
                new RejectProfessionalRequest("no longer want them")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.PROFESSIONAL_APPROVAL_INVALID_TRANSITION));
        assertThat(professional.getApprovalStatus()).isEqualTo(Professional.STATUS_APPROVED);
        verify(professionalRepository, never()).save(any());
    }

    @Test
    void decisionsOnAnUnknownProfessionalAre404() {
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(operator, PROFESSIONAL_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---- queue ----

    @Test
    void list_filtersByApprovalStatus() {
        when(professionalRepository.findByApprovalStatusOrderByCreatedAtAsc(Professional.STATUS_PENDING))
                .thenReturn(List.of(pendingProfessional()));
        when(professionalRepository.hasCompleteOnboarding(PROFESSIONAL_ID)).thenReturn(true);

        ProfessionalApprovalListResponse response = service.list("PENDING");

        assertThat(response.professionals()).hasSize(1);
        assertThat(response.professionals().get(0).approvalStatus()).isEqualTo(Professional.STATUS_PENDING);
        assertThat(response.professionals().get(0).onboardingComplete()).isTrue();
    }

    @Test
    void list_blankFilterMeansEverything() {
        when(professionalRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of());

        assertThat(service.list("  ").professionals()).isEmpty();
        assertThat(service.list(null).professionals()).isEmpty();
        verify(professionalRepository, never()).findByApprovalStatusOrderByCreatedAtAsc(anyString());
    }

    @Test
    void list_unrecognizedFilterIsRejected_notSilentlyEmpty() {
        // A typo returning "nobody is waiting" is the one wrong answer this screen can give.
        assertThatThrownBy(() -> service.list("PENDING_REVIEW"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // ---- verification document ----

    @Test
    void verificationDocument_mintsAShortLivedUrlForTheKeyOnTheRow() {
        stubExists(pendingProfessional());
        when(storageClient.presignUrl(DOCUMENT_KEY, Duration.ofSeconds(300)))
                .thenReturn("https://storage.example/signed");

        VerificationDocumentUrlResponse response = service.getVerificationDocumentUrl(operator, PROFESSIONAL_ID);

        assertThat(response.professionalId()).isEqualTo(PROFESSIONAL_ID);
        assertThat(response.url()).isEqualTo("https://storage.example/signed");
        assertThat(response.expiresInSeconds()).isEqualTo(300);
    }

    @Test
    void verificationDocument_responseNeverCarriesTheObjectKey() {
        // The URL expires; the key does not. Handing an operator's browser the durable half of the
        // secret would outlive every protection the TTL provides.
        stubExists(pendingProfessional());
        when(storageClient.presignUrl(anyString(), any())).thenReturn("https://storage.example/signed");

        VerificationDocumentUrlResponse response = service.getVerificationDocumentUrl(operator, PROFESSIONAL_ID);

        assertThat(response.toString()).doesNotContain(DOCUMENT_KEY);
        assertThat(java.util.Arrays.stream(VerificationDocumentUrlResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("key", "objectKey", "documentKey");
    }

    @Test
    void verificationDocument_absentDocumentIs404_noUrlMinted() {
        Professional professional = pendingProfessional();
        professional.setVerificationDocumentKey(null);
        stubExists(professional);

        assertThatThrownBy(() -> service.getVerificationDocumentUrl(operator, PROFESSIONAL_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(storageClient, never()).presignUrl(anyString(), any());
    }

    @Test
    void reviewDetail_neverEmbedsADocumentUrl() {
        // A URL on the detail response would mint a bearer capability on every list-then-open
        // traversal, whether or not anyone looked at the document.
        stubExists(pendingProfessional());

        ProfessionalReviewDetailResponse response = service.getReviewDetail(PROFESSIONAL_ID);

        assertThat(response.hasVerificationDocument()).isTrue();
        assertThat(java.util.Arrays.stream(ProfessionalReviewDetailResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("verificationDocumentUrl", "verificationDocumentKey");
        verify(storageClient, never()).presignUrl(anyString(), any());
    }
}
