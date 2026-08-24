package com.pronto.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import com.pronto.professionals.entity.ProfessionalServiceCity;
import com.pronto.locations.entity.ServiceCity;
import com.pronto.locations.repository.ServiceCityRepository;
import com.pronto.locations.repository.ServiceRegionRepository;
import com.pronto.locations.service.ServiceCoverageValidator;
import com.pronto.professionals.entity.Category;
import com.pronto.professionals.entity.ProfessionalCategory;
import com.pronto.professionals.repository.ProfessionalCategoryRepository;
import com.pronto.professionals.repository.ProfessionalServiceCityRepository;
import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.auth.dto.CustomerRegistrationData;
import com.pronto.auth.dto.DefaultAddressRequest;
import com.pronto.auth.dto.ProfessionalRegistrationData;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.auth.dto.RegisterResponse;
import com.pronto.auth.email.EmailSender;
import com.pronto.auth.repository.VerificationCodeRepository;
import com.pronto.auth.security.JwtService;
import com.pronto.availability.dto.WorkingHoursItemRequest;
import com.pronto.availability.entity.ProfessionalWorkingHours;
import com.pronto.availability.entity.SosAvailability;
import com.pronto.availability.repository.ProfessionalWorkingHoursRepository;
import com.pronto.availability.repository.SosAvailabilityRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.entity.ProfessionalSubService;
import com.pronto.professionals.entity.SubService;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ProfessionalSubServiceRepository;
import com.pronto.professionals.repository.SubServiceRepository;
import com.pronto.professionals.service.SubServiceSelectionValidator;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
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
    private static final Long OTHER_CATEGORY_ID = 4L;
    private static final Long SUB_SERVICE_ID = 55L;
    private static final Long CROSS_CATEGORY_SUB_SERVICE_ID = 66L;
    /** MS4: the closed service-area catalogue registration now validates against. */
    private static final Long SERVICE_REGION_ID = 4L;
    private static final Long BASE_CITY_ID = 40L;
    private static final Long SECOND_CITY_ID = 41L;
    /** A real city, in a different region -- the cross-region rule's negative case. */
    private static final Long OTHER_REGION_CITY_ID = 70L;
    private static final Long OTHER_REGION_ID = 5L;

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
    private SubServiceRepository subServiceRepository;
    private ProfessionalSubServiceRepository professionalSubServiceRepository;
    private ProfessionalCategoryRepository professionalCategoryRepository;
    private ProfessionalServiceCityRepository professionalServiceCityRepository;
    private ServiceRegionRepository serviceRegionRepository;
    private ServiceCityRepository serviceCityRepository;
    private ProfessionalWorkingHoursRepository professionalWorkingHoursRepository;
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

        subServiceRepository = Mockito.mock(SubServiceRepository.class);
        professionalSubServiceRepository = Mockito.mock(ProfessionalSubServiceRepository.class);
        professionalWorkingHoursRepository = Mockito.mock(ProfessionalWorkingHoursRepository.class);
        // A real validator over a mocked repository, same "real collaborator over the client
        // boundary" choice this class already makes for StorageService -- so these tests exercise
        // the actual cross-category rule registration now shares with the edit endpoint.
        SubServiceSelectionValidator subServiceSelectionValidator =
                new SubServiceSelectionValidator(subServiceRepository);
        professionalCategoryRepository = Mockito.mock(ProfessionalCategoryRepository.class);
        professionalServiceCityRepository = Mockito.mock(ProfessionalServiceCityRepository.class);
        serviceRegionRepository = Mockito.mock(ServiceRegionRepository.class);
        serviceCityRepository = Mockito.mock(ServiceCityRepository.class);
        // MS4: real ServiceCoverageValidator / ProfessionalCoverageService over mocked
        // repositories, the same "real collaborator over the client boundary" choice this class
        // already makes for StorageService and SubServiceSelectionValidator -- so these tests
        // exercise the actual region/city and category rules registration shares with the
        // profile-edit endpoint, rather than a stub that would agree with anything.
        ServiceCoverageValidator serviceCoverageValidator =
                new ServiceCoverageValidator(serviceRegionRepository, serviceCityRepository);
        ProfessionalCoverageService professionalCoverageService = new ProfessionalCoverageService(
                professionalCategoryRepository, professionalServiceCityRepository, serviceRegionRepository,
                serviceCityRepository, categoryRepository, serviceCoverageValidator);
        Mockito.lenient().when(serviceRegionRepository.existsById(SERVICE_REGION_ID)).thenReturn(true);
        Mockito.lenient().when(serviceRegionRepository.existsById(OTHER_REGION_ID)).thenReturn(true);
        Mockito.lenient().when(serviceCityRepository.findAllById(any())).thenAnswer(inv -> {
            List<ServiceCity> found = new ArrayList<>();
            for (Long id : (Iterable<Long>) inv.getArgument(0)) {
                if (BASE_CITY_ID.equals(id)) {
                    found.add(serviceCity(BASE_CITY_ID, SERVICE_REGION_ID, (short) 1));
                } else if (SECOND_CITY_ID.equals(id)) {
                    found.add(serviceCity(SECOND_CITY_ID, SERVICE_REGION_ID, (short) 2));
                } else if (OTHER_REGION_CITY_ID.equals(id)) {
                    found.add(serviceCity(OTHER_REGION_CITY_ID, OTHER_REGION_ID, (short) 1));
                }
            }
            return found;
        });
        when(subServiceRepository.findAllById(any())).thenAnswer(inv -> {
            List<SubService> found = new ArrayList<>();
            for (Long id : (Iterable<Long>) inv.getArgument(0)) {
                if (SUB_SERVICE_ID.equals(id)) {
                    found.add(subService(SUB_SERVICE_ID, CATEGORY_ID));
                } else if (CROSS_CATEGORY_SUB_SERVICE_ID.equals(id)) {
                    found.add(subService(CROSS_CATEGORY_SUB_SERVICE_ID, OTHER_CATEGORY_ID));
                }
            }
            return found;
        });

        authService = new AuthService(userRepository, professionalRepository, sosAvailabilityRepository,
                professionalCoverageService, serviceCoverageValidator, verificationCodeRepository,
                passwordEncoder, emailSender, jwtService, loginAttemptRecorder, storageService,
                subServiceSelectionValidator, professionalSubServiceRepository,
                professionalCategoryRepository, professionalServiceCityRepository,
                professionalWorkingHoursRepository);

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
        when(categoryRepository.findAllById(any())).thenAnswer(inv -> {
            List<Category> found = new ArrayList<>();
            for (Long id : (Iterable<Long>) inv.getArgument(0)) {
                if (CATEGORY_ID.equals(id) || OTHER_CATEGORY_ID.equals(id)) {
                    found.add(category(id));
                }
            }
            return found;
        });
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

    /** {@code Category}/{@code ServiceCity} have no public constructor (read-only reference entities). */
    private static <T> T readOnlyEntity(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Category category(Long id) {
        Category category = readOnlyEntity(Category.class);
        setField(category, "id", id);
        return category;
    }

    private static ServiceCity serviceCity(Long id, Long regionId, short displayOrder) {
        ServiceCity city = readOnlyEntity(ServiceCity.class);
        setField(city, "id", id);
        setField(city, "regionId", regionId);
        setField(city, "displayOrder", displayOrder);
        setField(city, "nameHe", "עיר " + id);
        return city;
    }

    /** {@code SubService} has no public constructor (read-only reference entity). */
    private static SubService subService(Long id, Long categoryId) {
        SubService subService;
        try {
            var constructor = SubService.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            subService = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        setField(subService, "id", id);
        setField(subService, "categoryId", categoryId);
        return subService;
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
        return new ProfessionalRegistrationData(List.of(CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"),
                List.of(SUB_SERVICE_ID), fullWeek(true));
    }

    /**
     * MS1: all 7 weekdays, as {@code PUT /api/availability/working-hours} takes them.
     * {@code sundayEnabled} controls whether ANY day is on — the all-disabled week is what the
     * "at least one enabled day" rule has to refuse.
     */
    private static List<WorkingHoursItemRequest> fullWeek(boolean sundayEnabled) {
        List<WorkingHoursItemRequest> week = new ArrayList<>();
        week.add(new WorkingHoursItemRequest(0, sundayEnabled,
                sundayEnabled ? LocalTime.of(8, 0) : null, sundayEnabled ? LocalTime.of(17, 0) : null));
        for (int weekday = 1; weekday <= 6; weekday++) {
            week.add(new WorkingHoursItemRequest(weekday, false, null, null));
        }
        return week;
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
        assertThat(saved.getServiceRegionId()).isEqualTo(SERVICE_REGION_ID);
        assertThat(saved.getBaseCityId()).isEqualTo(BASE_CITY_ID);
        assertThat(saved.getApprovalStatus()).isEqualTo(Professional.STATUS_PENDING);
        // MS4: the category lives in professional_categories now, not on the row.
        ArgumentCaptor<ProfessionalCategory> categoryCaptor =
                ArgumentCaptor.forClass(ProfessionalCategory.class);
        verify(professionalCategoryRepository).save(categoryCaptor.capture());
        assertThat(categoryCaptor.getValue().getCategoryId()).isEqualTo(CATEGORY_ID);
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
    void register_professional_missingCategoryIds_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(null, SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, BigDecimal.TEN, List.of(SUB_SERVICE_ID), fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void register_professional_invalidCategoryId_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(999L), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, BigDecimal.TEN, List.of(SUB_SERVICE_ID), fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_missingServiceRegion_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID), null,
                List.of(BASE_CITY_ID), BASE_CITY_ID, BigDecimal.TEN, List.of(SUB_SERVICE_ID), fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void register_professional_missingBasePrice_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, null, List.of(SUB_SERVICE_ID), fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void register_professional_negativeBasePrice_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("-5"), List.of(SUB_SERVICE_ID), fullWeek(true));

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
                sosAvailabilityRepository,
                new ProfessionalCoverageService(professionalCategoryRepository, professionalServiceCityRepository,
                        serviceRegionRepository, serviceCityRepository, categoryRepository,
                        new ServiceCoverageValidator(serviceRegionRepository, serviceCityRepository)),
                new ServiceCoverageValidator(serviceRegionRepository, serviceCityRepository),
                verificationCodeRepository, passwordEncoder, emailSender, jwtService, loginAttemptRecorder,
                failingStorageService, new SubServiceSelectionValidator(subServiceRepository),
                professionalSubServiceRepository, professionalCategoryRepository,
                professionalServiceCityRepository, professionalWorkingHoursRepository);

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
    void register_admin_rejected_adminIsNotSelfRegisterable() {
        // MS1 replaces the old "the enum has no ADMIN constant" test, which stopped being the
        // protection the moment UserRole.ADMIN existed. POST /api/auth/register's `role` field is
        // typed as the enum, so Jackson now binds "ADMIN" from a public, unauthenticated request
        // quite happily -- AuthService's explicit guard is the only thing between that request and
        // an operator account able to approve professionals. Refused before any row is written.
        RegisterRequest request = new RegisterRequest(UserRole.ADMIN, "Sneaky Admin", "admin@example.com",
                "StrongPassword123!", null, null);

        assertThatThrownBy(() -> authService.register(request, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
        verify(professionalRepository, never()).save(any());
        verify(verificationCodeRepository, never()).save(any());
        verify(emailSender, never()).sendVerificationCode(anyString(), anyString());
    }

    @Test
    void register_admin_rejectedEvenWithAnOtherwiseValidCustomerPayload() {
        // The guard must not be reachable-around by making the rest of the body look legitimate:
        // it is checked before, and independently of, every role-conditional field rule.
        RegisterRequest request = new RegisterRequest(UserRole.ADMIN, "Sneaky Admin", "admin@example.com",
                "StrongPassword123!", new CustomerRegistrationData(fullAddress(), VALID_PHONE), null);

        assertThatThrownBy(() -> authService.register(request, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
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
    void register_professional_alwaysStartsPending_noClientSuppliedApprovalStatus() {
        // MS1: registration hardcodes PENDING regardless of anything the client sends (there is
        // no field for it to send in the first place -- see the DTO-shape test above). This is
        // the Playbook's "new professional defaults to pending" required test.
        stubValidCategory();
        authService.register(professionalRequest(validProfessionalData()), pdfDocument(), null);

        ArgumentCaptor<Professional> captor = ArgumentCaptor.forClass(Professional.class);
        verify(professionalRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo(Professional.STATUS_PENDING);
        assertThat(captor.getValue().getApprovalReviewedAt()).isNull();
        assertThat(captor.getValue().getApprovalReviewedBy()).isNull();
    }

    // --- MS1: registration onboarding completeness (D4/D7) ---------------------------

    @Test
    void register_professional_persistsSelectedSubServices() {
        stubValidCategory();
        authService.register(professionalRequest(validProfessionalData()), pdfDocument(), null);

        ArgumentCaptor<ProfessionalSubService> captor = ArgumentCaptor.forClass(ProfessionalSubService.class);
        verify(professionalSubServiceRepository).save(captor.capture());
        assertThat(captor.getValue().getProfessionalId()).isEqualTo(200L);
        assertThat(captor.getValue().getSubServiceId()).isEqualTo(SUB_SERVICE_ID);
    }

    @Test
    void register_professional_persistsAllSevenWeekdays_disabledDaysCarryNoTimes() {
        stubValidCategory();
        authService.register(professionalRequest(validProfessionalData()), pdfDocument(), null);

        ArgumentCaptor<ProfessionalWorkingHours> captor =
                ArgumentCaptor.forClass(ProfessionalWorkingHours.class);
        verify(professionalWorkingHoursRepository, times(7)).save(captor.capture());
        List<ProfessionalWorkingHours> saved = captor.getAllValues();
        assertThat(saved).hasSize(7);
        assertThat(saved.get(0).isEnabled()).isTrue();
        assertThat(saved.get(0).getStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(saved.get(0).getEndTime()).isEqualTo(LocalTime.of(17, 0));
        // ck_professional_working_hours_times requires NULL times on a disabled day.
        assertThat(saved.subList(1, 7)).allSatisfy(row -> {
            assertThat(row.isEnabled()).isFalse();
            assertThat(row.getStartTime()).isNull();
            assertThat(row.getEndTime()).isNull();
        });
    }

    @Test
    void register_professional_noSubServices_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"), List.of(), fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_nullSubServices_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"), null, fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_crossCategorySubService_rejected() {
        // The rule the backend must own rather than trust the UI with: a sub-service belonging to
        // another category. Same CATEGORY_MISMATCH the edit endpoint raises -- one validator.
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"), List.of(CROSS_CATEGORY_SUB_SERVICE_ID), fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.CATEGORY_MISMATCH));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_unknownSubService_rejected() {
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"), List.of(9999L), fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_missingWorkingHours_rejected() {
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"), List.of(SUB_SERVICE_ID), null);

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_allDaysDisabled_rejected() {
        // The whole point of D4's working-hours rule: a professional with a week of switched-off
        // days derives an empty calendar and can never actually be booked.
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"), List.of(SUB_SERVICE_ID), fullWeek(false));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_partialWeek_rejected() {
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"), List.of(SUB_SERVICE_ID),
                List.of(new WorkingHoursItemRequest(0, true, LocalTime.of(8, 0), LocalTime.of(17, 0))));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_duplicateSubServiceIds_insertedOnce() {
        // A duplicate id in the payload must not become a primary-key violation on
        // professional_sub_services.
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"), List.of(SUB_SERVICE_ID, SUB_SERVICE_ID), fullWeek(true));

        authService.register(professionalRequest(data), pdfDocument(), null);

        verify(professionalSubServiceRepository, times(1)).save(any(ProfessionalSubService.class));
    }

    // ---- MS4: multiple categories, controlled service coverage, and the registration week ----

    @Test
    void register_professional_singleCategory_writesExactlyOneCategoryRow() {
        stubValidCategory();

        authService.register(professionalRequest(validProfessionalData()), pdfDocument(), null);

        ArgumentCaptor<ProfessionalCategory> captor = ArgumentCaptor.forClass(ProfessionalCategory.class);
        verify(professionalCategoryRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getProfessionalId()).isEqualTo(200L);
        assertThat(captor.getValue().getCategoryId()).isEqualTo(CATEGORY_ID);
    }

    @Test
    void register_professional_multipleCategories_writesOneRowPerCategory() {
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(
                List.of(CATEGORY_ID, OTHER_CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID,
                new BigDecimal("250.00"), List.of(SUB_SERVICE_ID, CROSS_CATEGORY_SUB_SERVICE_ID), fullWeek(true));

        authService.register(professionalRequest(data), pdfDocument(), null);

        ArgumentCaptor<ProfessionalCategory> captor = ArgumentCaptor.forClass(ProfessionalCategory.class);
        verify(professionalCategoryRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ProfessionalCategory::getCategoryId)
                .containsExactlyInAnyOrder(CATEGORY_ID, OTHER_CATEGORY_ID);
    }

    @Test
    void register_professional_multipleCategories_acceptsASubServiceUnderEitherOfThem() {
        // CROSS_CATEGORY_SUB_SERVICE_ID belongs to OTHER_CATEGORY_ID. Registering for that
        // category too makes it legal -- which is the whole of the MS4 sub-service rule change.
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(
                List.of(CATEGORY_ID, OTHER_CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID,
                new BigDecimal("250.00"), List.of(CROSS_CATEGORY_SUB_SERVICE_ID), fullWeek(true));

        assertThatCode(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void register_professional_subServiceUnderACategoryTheyDidNotRegisterFor_isStillRejected() {
        // The rule widened, it did not disappear: a sub-service under a trade the registrant
        // never claimed is exactly as illegal as it was before MS4.
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID),
                SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"),
                List.of(CROSS_CATEGORY_SUB_SERVICE_ID), fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.CATEGORY_MISMATCH));
    }

    @Test
    void register_professional_duplicateCategoryIds_insertedOnce() {
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(
                List.of(CATEGORY_ID, CATEGORY_ID), SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID,
                new BigDecimal("250.00"), List.of(SUB_SERVICE_ID), fullWeek(true));

        authService.register(professionalRequest(data), pdfDocument(), null);

        verify(professionalCategoryRepository, times(1)).save(any(ProfessionalCategory.class));
    }

    @Test
    void register_professional_multipleServiceCities_areAllPersisted() {
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID),
                SERVICE_REGION_ID, List.of(BASE_CITY_ID, SECOND_CITY_ID), BASE_CITY_ID,
                new BigDecimal("250.00"), List.of(SUB_SERVICE_ID), fullWeek(true));

        authService.register(professionalRequest(data), pdfDocument(), null);

        ArgumentCaptor<ProfessionalServiceCity> captor = ArgumentCaptor.forClass(ProfessionalServiceCity.class);
        verify(professionalServiceCityRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ProfessionalServiceCity::getCityId)
                .containsExactlyInAnyOrder(BASE_CITY_ID, SECOND_CITY_ID);
    }

    @Test
    void register_professional_regionAndBaseCityLandOnTheRow() {
        stubValidCategory();

        authService.register(professionalRequest(validProfessionalData()), pdfDocument(), null);

        ArgumentCaptor<Professional> captor = ArgumentCaptor.forClass(Professional.class);
        verify(professionalRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().getServiceRegionId()).isEqualTo(SERVICE_REGION_ID);
        assertThat(captor.getValue().getBaseCityId()).isEqualTo(BASE_CITY_ID);
    }

    @Test
    void register_professional_unknownServiceCity_rejectedBeforeAnyRowIsWritten() {
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID),
                SERVICE_REGION_ID, List.of(9_999L), 9_999L, new BigDecimal("250.00"),
                List.of(SUB_SERVICE_ID), fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_cityOutsideTheChosenRegion_rejected() {
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID),
                SERVICE_REGION_ID, List.of(BASE_CITY_ID, OTHER_REGION_CITY_ID), BASE_CITY_ID,
                new BigDecimal("250.00"), List.of(SUB_SERVICE_ID), fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_professional_baseCityNotAmongTheServiceCities_rejected() {
        stubValidCategory();
        ProfessionalRegistrationData data = new ProfessionalRegistrationData(List.of(CATEGORY_ID),
                SERVICE_REGION_ID, List.of(BASE_CITY_ID), SECOND_CITY_ID, new BigDecimal("250.00"),
                List.of(SUB_SERVICE_ID), fullWeek(true));

        assertThatThrownBy(() -> authService.register(professionalRequest(data), pdfDocument(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    /**
     * MS4 §11 — "apply to all". The button is a frontend affordance, but what it produces is an
     * ordinary week where several days carry the same pair, and this is the backend half of that
     * promise: the week is stored exactly as sent, with no server-side normalisation of the
     * repeated values into something else.
     */
    @Test
    void register_professional_uniformWeek_persistsTheSameHoursOnEveryEnabledDay() {
        stubValidCategory();
        List<WorkingHoursItemRequest> week = new ArrayList<>();
        for (int weekday = 0; weekday <= 4; weekday++) {   // Sunday-Thursday 08:00-17:00
            week.add(new WorkingHoursItemRequest(weekday, true, LocalTime.of(8, 0), LocalTime.of(17, 0)));
        }
        week.add(new WorkingHoursItemRequest(5, false, null, null));
        week.add(new WorkingHoursItemRequest(6, false, null, null));

        authService.register(professionalRequest(new ProfessionalRegistrationData(List.of(CATEGORY_ID),
                SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"),
                List.of(SUB_SERVICE_ID), week)), pdfDocument(), null);

        ArgumentCaptor<ProfessionalWorkingHours> captor =
                ArgumentCaptor.forClass(ProfessionalWorkingHours.class);
        verify(professionalWorkingHoursRepository, times(7)).save(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(ProfessionalWorkingHours::isEnabled)
                .hasSize(5)
                .allSatisfy(row -> {
                    assertThat(row.getStartTime()).isEqualTo(LocalTime.of(8, 0));
                    assertThat(row.getEndTime()).isEqualTo(LocalTime.of(17, 0));
                });
        // A day that is off carries no times at all -- what ck_professional_working_hours_times
        // is built around, and what keeps a "closed" day from reading as 00:00-00:00.
        assertThat(captor.getAllValues()).filteredOn(row -> !row.isEnabled())
                .hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.getStartTime()).isNull();
                    assertThat(row.getEndTime()).isNull();
                });
    }

    /** MS4 §12 — an individual day overrides the common hours, and only that day changes. */
    @Test
    void register_professional_perDayOverride_changesOnlyThatDay() {
        stubValidCategory();
        List<WorkingHoursItemRequest> week = new ArrayList<>();
        for (int weekday = 0; weekday <= 4; weekday++) {
            week.add(new WorkingHoursItemRequest(weekday, true, LocalTime.of(8, 0), LocalTime.of(17, 0)));
        }
        // Thursday (weekday 4) overridden to 08:00-14:00 after the bulk apply.
        week.set(4, new WorkingHoursItemRequest(4, true, LocalTime.of(8, 0), LocalTime.of(14, 0)));
        week.add(new WorkingHoursItemRequest(5, false, null, null));
        week.add(new WorkingHoursItemRequest(6, false, null, null));

        authService.register(professionalRequest(new ProfessionalRegistrationData(List.of(CATEGORY_ID),
                SERVICE_REGION_ID, List.of(BASE_CITY_ID), BASE_CITY_ID, new BigDecimal("250.00"),
                List.of(SUB_SERVICE_ID), week)), pdfDocument(), null);

        ArgumentCaptor<ProfessionalWorkingHours> captor =
                ArgumentCaptor.forClass(ProfessionalWorkingHours.class);
        verify(professionalWorkingHoursRepository, times(7)).save(captor.capture());
        assertThat(captor.getAllValues())
                .filteredOn(row -> row.getWeekday() == 4)
                .singleElement()
                .satisfies(row -> assertThat(row.getEndTime()).isEqualTo(LocalTime.of(14, 0)));
        assertThat(captor.getAllValues())
                .filteredOn(row -> row.isEnabled() && row.getWeekday() != 4)
                .hasSize(4)
                .allSatisfy(row -> assertThat(row.getEndTime()).isEqualTo(LocalTime.of(17, 0)));
    }
}
