package com.pronto.auth.service;

import com.pronto.auth.dto.CustomerRegistrationData;
import com.pronto.auth.dto.DefaultAddressRequest;
import com.pronto.auth.dto.ProfessionalRegistrationData;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.auth.dto.RegisterResponse;
import com.pronto.auth.email.EmailSender;
import com.pronto.auth.repository.VerificationCodeRepository;
import com.pronto.auth.security.JwtService;
import com.pronto.availability.entity.SosAvailability;
import com.pronto.availability.repository.SosAvailabilityRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.client.StorageException;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthService#register} — the backend registration flow
 * separation task's core behavior. Repositories/collaborators are Mockito mocks (no
 * Spring context, no real database — matches this codebase's existing unit-test
 * convention, e.g. {@code professionals.service.ProfessionalsServiceTest}), so
 * {@code @Transactional} rollback itself is a Spring/DB-integration concern this suite
 * can't exercise directly; {@link #register_professional_documentUploadFailure_propagatesAndDoesNotSwallow}
 * instead verifies the contract {@code @Transactional} on {@link AuthService#register}
 * relies on — that a failure after the {@code User} row is saved still propagates as an
 * exception rather than being swallowed, which is what makes Spring's rollback-on-
 * exception behavior apply in a real deployment.
 */
class AuthServiceTest {

    private static final Long CATEGORY_ID = 3L;

    private UserRepository userRepository;
    private ProfessionalRepository professionalRepository;
    private SosAvailabilityRepository sosAvailabilityRepository;
    private CategoryRepository categoryRepository;
    private VerificationCodeRepository verificationCodeRepository;
    private PasswordEncoder passwordEncoder;
    private EmailSender emailSender;
    private JwtService jwtService;
    private LoginAttemptRecorder loginAttemptRecorder;
    private StorageService storageService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        sosAvailabilityRepository = Mockito.mock(SosAvailabilityRepository.class);
        categoryRepository = Mockito.mock(CategoryRepository.class);
        verificationCodeRepository = Mockito.mock(VerificationCodeRepository.class);
        passwordEncoder = Mockito.mock(PasswordEncoder.class);
        emailSender = Mockito.mock(EmailSender.class);
        jwtService = Mockito.mock(JwtService.class);
        loginAttemptRecorder = Mockito.mock(LoginAttemptRecorder.class);
        // A real StorageService (not a mock) backed by a mocked StorageClient: exercises
        // the actual content-type/size validation + key handling AuthService relies on,
        // same "real collaborator over the client boundary" choice
        // professionals.service.ProfessionalsServiceTest makes for StorageClient.
        StorageClient storageClient = Mockito.mock(StorageClient.class);
        storageService = new StorageService(storageClient, Optional.empty(), 300L);
        when(storageClient.upload(anyString(), any(), anyString())).thenAnswer(inv ->
                new com.pronto.storage.client.StoredObject(
                        inv.getArgument(0), "http://localhost/x", inv.getArgument(2), 5));

        authService = new AuthService(userRepository, professionalRepository, sosAvailabilityRepository,
                categoryRepository, verificationCodeRepository, passwordEncoder, emailSender, jwtService,
                loginAttemptRecorder, storageService);

        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            if (user.getId() == null) {
                setField(user, "id", 100L);
            }
            return user;
        });
        when(professionalRepository.save(any(Professional.class))).thenAnswer(inv -> {
            Professional professional = inv.getArgument(0);
            if (professional.getId() == null) {
                setField(professional, "id", 200L);
            }
            return professional;
        });
        // Deliberately NOT stubbed here (only per-test, where actually needed): a
        // when(...) call is itself a recorded invocation, which would make
        // register_customer_professionalPayloadNotRequired_evenIfCategoryRepositoryUnstubbedForIt's
        // verifyNoInteractions(categoryRepository) assertion meaningless if this ran
        // unconditionally for every test.
    }

    private void stubValidCategory() {
        when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(true);
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

    private static final String VALID_PHONE = "0501234567";

    private static RegisterRequest customerRequest(DefaultAddressRequest address) {
        return customerRequest(address, VALID_PHONE);
    }

    private static RegisterRequest customerRequest(DefaultAddressRequest address, String phone) {
        return new RegisterRequest(UserRole.CUSTOMER, "Israel Israeli", "customer@example.com",
                "StrongPassword123!", address == null ? null : new CustomerRegistrationData(address, phone), null);
    }

    private static DefaultAddressRequest fullAddress() {
        return new DefaultAddressRequest("Tel Aviv", "Dizengoff", "100", "4", "2", "A", "Back entrance");
    }

    private static RegisterRequest professionalRequest(ProfessionalRegistrationData data) {
        return new RegisterRequest(UserRole.PROFESSIONAL, "David Cohen", "professional@example.com",
                "StrongPassword123!", null, data);
    }

    private static ProfessionalRegistrationData validProfessionalData() {
        return new ProfessionalRegistrationData(CATEGORY_ID, "Tel Aviv", new BigDecimal("250.00"));
    }

    private static MockMultipartFile pdfDocument() {
        return new MockMultipartFile("verificationDocument", "license.pdf", "application/pdf", "doc".getBytes());
    }

    private static MockMultipartFile jpegPhoto() {
        return new MockMultipartFile("profilePhoto", "me.jpg", "image/jpeg", "photo".getBytes());
    }

    // --- Customer registration ---------------------------------------------------

    @Test
    void register_customer_validAddress_succeedsAndPersistsDefaultAddress() {
        RegisterRequest request = customerRequest(fullAddress());

        RegisterResponse response = authService.register(request, null, null);

        assertThat(response.role()).isEqualTo(UserRole.CUSTOMER);
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getDefaultCity()).isEqualTo("Tel Aviv");
        assertThat(saved.getDefaultStreet()).isEqualTo("Dizengoff");
        assertThat(saved.getDefaultHouseNumber()).isEqualTo("100");
        assertThat(saved.getDefaultApartment()).isEqualTo("4");
        assertThat(saved.getDefaultFloor()).isEqualTo("2");
        assertThat(saved.getDefaultEntrance()).isEqualTo("A");
        assertThat(saved.getDefaultAddressNotes()).isEqualTo("Back entrance");
        assertThat(saved.getPhone()).isEqualTo(VALID_PHONE);
    }

    @Test
    void register_customer_optionalAddressFieldsOmitted_stillSucceeds() {
        DefaultAddressRequest minimal = new DefaultAddressRequest("Haifa", "Herzl", "5", null, null, null, null);

        RegisterResponse response = authService.register(customerRequest(minimal), null, null);

        assertThat(response.role()).isEqualTo(UserRole.CUSTOMER);
    }

    @Test
    void register_customer_missingCustomerPayload_rejectedWithFieldError() {
        assertThatThrownBy(() -> authService.register(customerRequest(null), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void register_customer_doesNotCreateProfessionalOrSosAvailabilityRow() {
        authService.register(customerRequest(fullAddress()), null, null);

        verify(professionalRepository, never()).save(any());
        verify(sosAvailabilityRepository, never()).save(any());
    }

    @Test
    void register_customer_professionalPayloadNotRequired_evenIfCategoryRepositoryUnstubbedForIt() {
        // No professional-only validation should run at all for a CUSTOMER registration.
        RegisterResponse response = authService.register(customerRequest(fullAddress()), null, null);

        assertThat(response.emailVerified()).isFalse();
        Mockito.verifyNoInteractions(categoryRepository);
    }

    // --- Professional registration -------------------------------------------------

    @Test
    void register_professional_valid_neverSetsPhone() {
        // §9.1 of the professional weekly availability calendar design: phone is collected
        // for CUSTOMER registration only -- a PROFESSIONAL row's users.phone stays NULL.
        stubValidCategory();
        authService.register(professionalRequest(validProfessionalData()), pdfDocument(), null);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPhone()).isNull();
    }

    @Test
    void register_professional_valid_succeedsAndLinksProfessionalToUser() {
        stubValidCategory();
        RegisterResponse response = authService.register(
                professionalRequest(validProfessionalData()), pdfDocument(), null);

        assertThat(response.role()).isEqualTo(UserRole.PROFESSIONAL);
        ArgumentCaptor<Professional> professionalCaptor = ArgumentCaptor.forClass(Professional.class);
        verify(professionalRepository, times(2)).save(professionalCaptor.capture());
        Professional saved = professionalCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(100L);
        assertThat(saved.getCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(saved.getApprovalStatus()).isEqualTo("APPROVED");
    }

    @Test
    void register_professional_verificationDocumentKeyPersists() {
        stubValidCategory();
        authService.register(professionalRequest(validProfessionalData()), pdfDocument(), null);

        ArgumentCaptor<Professional> captor = ArgumentCaptor.forClass(Professional.class);
        verify(professionalRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().getVerificationDocumentKey())
                .startsWith("verification-documents/100/")
                .endsWith(".pdf");
    }

    @Test
    void register_professional_profilePhotoOptional_absentLeavesKeyNull() {
        stubValidCategory();
        authService.register(professionalRequest(validProfessionalData()), pdfDocument(), null);

        ArgumentCaptor<Professional> captor = ArgumentCaptor.forClass(Professional.class);
        verify(professionalRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().getProfileImageKey()).isNull();
    }

    @Test
    void register_professional_profilePhotoSupplied_keyPersists() {
        stubValidCategory();
        authService.register(professionalRequest(validProfessionalData()), pdfDocument(), jpegPhoto());

        ArgumentCaptor<Professional> captor = ArgumentCaptor.forClass(Professional.class);
        verify(professionalRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().getProfileImageKey())
                .startsWith("professionals/200/profile/")
                .endsWith(".jpg");
    }

    @Test
    void register_professional_createsSosAvailabilityRowDefaultingUnavailable() {
        stubValidCategory();
        authService.register(professionalRequest(validProfessionalData()), pdfDocument(), null);

        ArgumentCaptor<SosAvailability> captor = ArgumentCaptor.forClass(SosAvailability.class);
        verify(sosAvailabilityRepository).save(captor.capture());
        assertThat(captor.getValue().getProfessionalId()).isEqualTo(200L);
        assertThat(captor.getValue().isAvailable()).isFalse();
    }

    @Test
    void register_professional_missingCategoryId_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(null, "Tel Aviv", BigDecimal.TEN);

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void register_professional_invalidCategoryId_rejected() {
        when(categoryRepository.existsById(999L)).thenReturn(false);
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(999L, "Tel Aviv", BigDecimal.TEN);

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_missingServiceArea_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(CATEGORY_ID, "  ", BigDecimal.TEN);

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void register_professional_missingBasePrice_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(CATEGORY_ID, "Tel Aviv", null);

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void register_professional_negativeBasePrice_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(CATEGORY_ID, "Tel Aviv", new BigDecimal("-5"));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void register_professional_missingVerificationDocument_rejected() {
        assertThatThrownBy(() -> authService.register(
                professionalRequest(validProfessionalData()), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_missingProfessionalPayload_rejected() {
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest(UserRole.PROFESSIONAL, "David Cohen", "professional@example.com",
                        "StrongPassword123!", null, null),
                pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // --- Transactionality (see class Javadoc for what this unit-test suite can/can't assert) ---

    @Test
    void register_professional_documentUploadFailure_propagatesAndDoesNotSwallow() {
        stubValidCategory();
        StorageClient failingClient = Mockito.mock(StorageClient.class);
        when(failingClient.upload(anyString(), any(), anyString())).thenThrow(new StorageException("disk full", null));
        StorageService failingStorageService = new StorageService(failingClient, Optional.empty(), 300L);
        AuthService serviceWithFailingStorage = new AuthService(userRepository, professionalRepository,
                sosAvailabilityRepository, categoryRepository, verificationCodeRepository, passwordEncoder,
                emailSender, jwtService, loginAttemptRecorder, failingStorageService);

        assertThatThrownBy(() -> serviceWithFailingStorage.register(
                professionalRequest(validProfessionalData()), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.STORAGE_SERVICE_ERROR));
        // The User row was written to the mocked repository (this test can't see a real
        // rollback without a Spring/DB integration test) -- what it does confirm is that
        // the failure surfaces as a propagating exception rather than being caught and
        // ignored, which is the precondition for @Transactional's rollback-on-exception
        // to apply in a real deployment.
        verify(userRepository).save(any(User.class));
        verify(verificationCodeRepository, never()).save(any());
        verify(emailSender, never()).sendVerificationCode(anyString(), anyString());
    }

    // --- Security --------------------------------------------------------------

    @Test
    void userRole_hasOnlyCustomerAndProfessional_noPubliclyRegisterableAdminRole() {
        // POST /api/auth/register's `role` field is typed as UserRole -- Jackson rejects
        // any JSON value outside this enum's constants as a malformed request body
        // (common.exception.GlobalExceptionHandler#handleUnreadable -> 400
        // VALIDATION_ERROR), before AuthService is ever invoked. This asserts the enum
        // itself carries no ADMIN/privileged constant a client could ever supply.
        assertThat(UserRole.values()).containsExactlyInAnyOrder(UserRole.CUSTOMER, UserRole.PROFESSIONAL);
    }

    @Test
    void registerRequest_hasNoClientControllableProtectedFields() {
        java.lang.reflect.RecordComponent[] components = RegisterRequest.class.getRecordComponents();
        java.util.Set<String> names = java.util.Arrays.stream(components)
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(names).doesNotContainAnyElementsOf(java.util.Set.of(
                "approvalStatus", "verificationStatus", "accountStatus", "rating", "reviewCount",
                "reliabilityScore", "id", "userId", "createdAt", "updatedAt", "deletedAt", "confirmPassword"));
    }

    @Test
    void register_professional_alwaysStartsApproved_noClientSuppliedApprovalStatus() {
        // v1.0 has no approval workflow (see Professional's own Javadoc) -- registration
        // always hardcodes APPROVED regardless of anything the client sends (there is no
        // field for it to send in the first place, see the DTO-shape test above).
        stubValidCategory();
        authService.register(professionalRequest(validProfessionalData()), pdfDocument(), null);

        ArgumentCaptor<Professional> captor = ArgumentCaptor.forClass(Professional.class);
        verify(professionalRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo("APPROVED");
    }
}
