package com.pronto.issues.service;

import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.service.ClassificationService;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.UploadOwner;
import com.pronto.issues.dto.ClassifyRequest;
import com.pronto.issues.dto.CreateIssueRequest;
import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueImage;
import com.pronto.issues.entity.IssueUrgencyType;
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
import com.pronto.users.service.ContactVerificationGuard;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens to a guest's photos on the way through classification and, eventually, onto a real
 * issue — the two places {@code issues} had to learn that a caller's proved identity might not be a
 * {@code users} row id.
 *
 * <p>{@code GuestUploadPolicyParityTest} covers the upload rules themselves; this covers the claim:
 * which keys this package will accept from whom, and what it does with the ones it accepts.
 */
class GuestIssueImagesTest {

    private static final String GUEST_ID = "2f1c9d8e-4b7a-4c3d-9e2f-1a2b3c4d5e6f";
    private static final String OTHER_GUEST_ID = "9a8b7c6d-5e4f-4a3b-8c9d-0e1f2a3b4c5d";
    private static final long CUSTOMER_ID = 42L;

    private static final String GUEST_KEY = guestKey(GUEST_ID, "aaaaaaaa-1111-2222-3333-444444444444.jpg");
    private static final String PROMOTED_KEY =
            "customers/42/issues/temp/aaaaaaaa-1111-2222-3333-444444444444.jpg";

    private ClassificationService classificationService;
    private StorageClient storageClient;
    private StorageService storageService;
    private IssueRepository issueRepository;
    private IssueImageRepository issueImageRepository;
    private CategoryRepository categoryRepository;
    private IssuesService issuesService;

    @BeforeEach
    void setUp() {
        classificationService = Mockito.mock(ClassificationService.class);
        storageClient = Mockito.mock(StorageClient.class);
        storageService = Mockito.mock(StorageService.class);
        issueRepository = Mockito.mock(IssueRepository.class);
        issueImageRepository = Mockito.mock(IssueImageRepository.class);
        categoryRepository = Mockito.mock(CategoryRepository.class);

        issuesService = new IssuesService(
                issueRepository,
                issueImageRepository,
                Mockito.mock(IssueClarificationRepository.class),
                Mockito.mock(IssueClassificationRepository.class),
                Mockito.mock(IssueBriefRepository.class),
                categoryRepository,
                storageClient,
                storageService,
                classificationService,
                Mockito.mock(ProfessionalRepository.class),
                Mockito.mock(OrderRepository.class),
                Mockito.mock(UserRepository.class),
                Mockito.mock(ContactVerificationGuard.class),
                Mockito.mock(ApplicationEventPublisher.class));

        when(storageClient.exists(anyString())).thenReturn(true);
    }

    // ---- 15. AI classification: guest images reach the model identically ----

    @Test
    void aGuestsImagesAreForwardedToClassificationExactlyAsACustomersAre() {
        when(classificationService.classify(anyString(), anyList(), any(), anyList())).thenReturn(classified());

        issuesService.classify(UploadOwner.guest(GUEST_ID),
                new ClassifyRequest("המזגן מטפטף מים על הרצפה", List.of(GUEST_KEY), null, null));

        // Images DO participate in classification (ai.service.ClassificationService resolves each
        // key to bytes via IssueImageResolver#resolveRequired), so this asserts the guest's keys
        // arrive there rather than being silently dropped for want of an account.
        verify(classificationService).classify(eq("המזגן מטפטף מים על הרצפה"), eq(List.of(GUEST_KEY)),
                eq(null), eq(List.of()));
    }

    @Test
    void anAuthenticatedCustomersClassificationIsUnchanged() {
        // 14/16, the regression half: the pre-existing path still forwards exactly what it did.
        when(classificationService.classify(anyString(), anyList(), any(), anyList())).thenReturn(classified());
        String customerKey = "customers/42/issues/temp/x.jpg";

        issuesService.classify(CUSTOMER_ID, new ClassifyRequest("נזילה", List.of(customerKey), null, null));

        verify(classificationService).classify(eq("נזילה"), eq(List.of(customerKey)), eq(null), eq(List.of()));
    }

    @Test
    void aGuestCannotClassifyWithAnotherGuestsImage() {
        assertThatThrownBy(() -> issuesService.classify(UploadOwner.guest(GUEST_ID),
                new ClassifyRequest("נזילה", List.of(guestKey(OTHER_GUEST_ID, "b.jpg")), null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(code(ErrorCode.IMAGE_KEY_INVALID));
        Mockito.verifyNoInteractions(classificationService);
    }

    @Test
    void anAnonymousCallerWithNoGuestSessionCannotAttachAnyImage() {
        // A guest key quoted by someone who proved nothing is refused with the same error a forged
        // customer key always produced -- no "has the guest prefix, therefore fine" shortcut exists.
        assertThatThrownBy(() -> issuesService.classify(new UploadOwner(null, null),
                new ClassifyRequest("נזילה", List.of(GUEST_KEY), null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(code(ErrorCode.IMAGE_KEY_INVALID));
    }

    // ---- 11. The commit: promotion onto the now-known customer ----

    @Test
    void creatingAnIssueAfterRegistrationPromotesTheGuestPhotosOntoTheAccount() {
        stubIssueCreation();
        when(storageService.promoteGuestImage(any(), eq(GUEST_KEY), eq(CUSTOMER_ID))).thenReturn(PROMOTED_KEY);

        issuesService.create(new UploadOwner(CUSTOMER_ID, GUEST_ID), createRequest(List.of(GUEST_KEY)));

        ArgumentCaptor<IssueImage> saved = ArgumentCaptor.forClass(IssueImage.class);
        verify(issueImageRepository).save(saved.capture());
        // issue_images records the CUSTOMER key, never the guest one. That is what keeps every
        // downstream read path -- getById, the batch presign, a resumed draft -- reading the single
        // key format it already understands, and stops a row outliving the session that owns it.
        assertThat(saved.getValue().getImageKey()).isEqualTo(PROMOTED_KEY);
    }

    @Test
    void aMixedListPromotesOnlyTheGuestKeys() {
        // The realistic auth-transition shape: some photos attached before registering, some after.
        stubIssueCreation();
        String customerKey = "customers/42/issues/temp/after.jpg";
        when(storageService.promoteGuestImage(any(), eq(GUEST_KEY), eq(CUSTOMER_ID))).thenReturn(PROMOTED_KEY);

        issuesService.create(new UploadOwner(CUSTOMER_ID, GUEST_ID),
                createRequest(List.of(GUEST_KEY, customerKey)));

        ArgumentCaptor<IssueImage> saved = ArgumentCaptor.forClass(IssueImage.class);
        verify(issueImageRepository, Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(IssueImage::getImageKey)
                .containsExactly(PROMOTED_KEY, customerKey);
        verify(storageService).promoteGuestImage(any(), eq(GUEST_KEY), eq(CUSTOMER_ID));
        verify(storageService, never()).promoteGuestImage(any(), eq(customerKey), anyLong());
    }

    @Test
    void aPurelyAuthenticatedCreateTouchesNoPromotionMachineryAtAll() {
        // 14: an ordinary customer's issue creation is byte-for-byte the operation it was.
        stubIssueCreation();
        String customerKey = "customers/42/issues/temp/x.jpg";

        issuesService.create(CUSTOMER_ID, createRequest(List.of(customerKey)));

        ArgumentCaptor<IssueImage> saved = ArgumentCaptor.forClass(IssueImage.class);
        verify(issueImageRepository).save(saved.capture());
        assertThat(saved.getValue().getImageKey()).isEqualTo(customerKey);
        verify(storageService, never()).promoteGuestImage(any(), anyString(), anyLong());
        verify(storageService, never()).discardPromotedGuestImage(anyString());
    }

    // ---- 12. Foreign image ids are refused at the commit ----

    @Test
    void anAuthenticatedCustomerCannotAttachAGuestKeyWithoutThatGuestsSession() {
        // The theft case. Quoting a key is not evidence; holding the token that named the namespace
        // is. Note this customer IS authenticated and IS creating their own issue -- the only thing
        // missing is the guest session, and that alone is enough to refuse.
        when(categoryRepository.existsById(anyLong())).thenReturn(true);

        assertThatThrownBy(() -> issuesService.create(UploadOwner.customer(CUSTOMER_ID),
                createRequest(List.of(GUEST_KEY))))
                .isInstanceOf(ApiException.class)
                .satisfies(code(ErrorCode.IMAGE_KEY_INVALID));
        verify(storageService, never()).promoteGuestImage(any(), anyString(), anyLong());
        verify(issueRepository, never()).save(any());
    }

    @Test
    void aGuestWhoRegisteredCannotAttachADifferentGuestsKey() {
        when(categoryRepository.existsById(anyLong())).thenReturn(true);

        assertThatThrownBy(() -> issuesService.create(new UploadOwner(CUSTOMER_ID, GUEST_ID),
                createRequest(List.of(guestKey(OTHER_GUEST_ID, "b.jpg")))))
                .isInstanceOf(ApiException.class)
                .satisfies(code(ErrorCode.IMAGE_KEY_INVALID));
        verify(issueRepository, never()).save(any());
    }

    @Test
    void aGuestKeyThatIsNotActuallyInStorageIsRefusedLikeAnyOtherMissingKey() {
        when(categoryRepository.existsById(anyLong())).thenReturn(true);
        when(storageClient.exists(GUEST_KEY)).thenReturn(false);

        assertThatThrownBy(() -> issuesService.create(new UploadOwner(CUSTOMER_ID, GUEST_ID),
                createRequest(List.of(GUEST_KEY))))
                .isInstanceOf(ApiException.class)
                .satisfies(code(ErrorCode.IMAGE_KEY_INVALID));
    }

    // ---- 13. Cleanup ordering ----

    @Test
    void theGuestOriginalIsNotDeletedInlineWithTheTransaction() {
        // Deleting it here would mean a rolled-back booking had destroyed the customer's photos,
        // and their retry -- still holding the guest key in their draft -- would fail. The delete is
        // registered as an after-commit callback instead; with no active transaction (as here, and
        // as in every unit test) it simply never fires, which is the safe direction.
        stubIssueCreation();
        when(storageService.promoteGuestImage(any(), eq(GUEST_KEY), eq(CUSTOMER_ID))).thenReturn(PROMOTED_KEY);

        issuesService.create(new UploadOwner(CUSTOMER_ID, GUEST_ID), createRequest(List.of(GUEST_KEY)));

        verify(storageService, never()).discardPromotedGuestImage(anyString());
    }

    // ---- 6. The max-image-count rule is a property of the request, not of the caller ----

    @Test
    void theSixImageCapIsTheSameConstraintForBothKindsOfOwner() {
        // The cap lives on the DTO as Bean Validation (@Size(max = 6)) and is therefore enforced by
        // Spring before either service method is entered, for every caller, with no reference to who
        // they are. Asserting it here -- against the same validator, with a guest's keys and a
        // customer's -- is what would fail if anyone ever introduced a per-owner limit.
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(createRequest(nKeys(6, GUEST_ID)))).isEmpty();
            assertThat(validator.validate(createRequest(nKeys(6, null)))).isEmpty();
            assertThat(validator.validate(createRequest(nKeys(7, GUEST_ID))))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("imageKeys");
            assertThat(validator.validate(createRequest(nKeys(7, null))))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("imageKeys");

            assertThat(validator.validate(new ClassifyRequest("המזגן מטפטף מים", nKeys(7, GUEST_ID), null, null)))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("imageKeys");
            assertThat(validator.validate(new ClassifyRequest("המזגן מטפטף מים", nKeys(7, null), null, null)))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("imageKeys");
        }
    }

    /** {@code n} keys in the guest namespace, or the customer's when {@code guestId} is null. */
    private static List<String> nKeys(int n, String guestId) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> guestId == null
                        ? "customers/42/issues/temp/" + i + ".jpg"
                        : guestKey(guestId, i + ".jpg"))
                .toList();
    }

    // ---- helpers ----

    private void stubIssueCreation() {
        when(categoryRepository.existsById(anyLong())).thenReturn(true);
        when(issueRepository.save(any(Issue.class))).thenAnswer(i -> i.getArgument(0));
        when(issueImageRepository.save(any(IssueImage.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static CreateIssueRequest createRequest(List<String> imageKeys) {
        return new CreateIssueRequest(3L, "המזגן מטפטף מים על הרצפה כבר יומיים",
                IssueUrgencyType.STANDARD, imageKeys, null);
    }

    private static ClassificationSuggestion classified() {
        return new ClassificationSuggestion(ClassificationStatus.CLASSIFIED, "טכנאי מזגנים", 3L, "ac_hvac",
                0.94, false, false, null, List.of(new CategoryCandidate("ac_hvac", 0.94)), List.of());
    }

    private static String guestKey(String guestId, String fileName) {
        return "guests/" + guestId + "/issues/temp/" + fileName;
    }

    private static Consumer<Throwable> code(ErrorCode expected) {
        return e -> assertThat(((ApiException) e).getCode()).isEqualTo(expected);
    }
}
