package com.pronto.professionals.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.favorites.repository.FavoriteRepository;
import com.pronto.professionals.dto.MySubServicesResponse;
import com.pronto.professionals.dto.ProfessionalProfileResponse;
import com.pronto.professionals.dto.UpdateProfessionalProfileRequest;
import com.pronto.professionals.dto.UpdateSubServicesRequest;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.entity.ProfessionalSubService;
import com.pronto.professionals.entity.ProfessionalSubServiceId;
import com.pronto.professionals.entity.SubService;
import com.pronto.professionals.repository.ProfessionalRatingAggregate;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ProfessionalSubServiceRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.professionals.repository.SubServiceRepository;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProfessionalsService#updateMyProfile}'s allowlist behavior — confirms
 * {@link UpdateProfessionalProfileRequest} genuinely has no field for
 * {@code id}/{@code userId}/{@code categoryId}/{@code approvalStatus}/
 * {@code reliabilityScore}/rating/reviewCount/timestamps, and that a full round-trip update
 * leaves those fields unchanged on the underlying entity. As of MS11 (Services &amp;
 * Sub-services), also covers {@link ProfessionalsService#getMySubServices}/
 * {@link ProfessionalsService#updateMySubServices} — category-mismatch rejection,
 * unknown-id rejection, diff-based update semantics (preserving unchanged rows), and
 * empty-list save. See {@code docs/architecture/product-ms11-sub-services-design.md} §3.2.
 */
class ProfessionalsServiceTest {

    /** MS4: `professionals` no longer stores a category or free-text place -- see the entity. */
    private static final long SERVICE_REGION_ID = 4L;
    private static final long BASE_CITY_ID = 40L;
    private static final long NEW_REGION_ID = 2L;
    private static final long NEW_BASE_CITY_ID = 17L;

    private static final Long CALLER_ID = 1L;
    private static final Long PROFESSIONAL_ID = 50L;
    private static final Long CATEGORY_ID = 3L;
    private static final Long OTHER_CATEGORY_ID = 4L;

    private ProfessionalRepository professionalRepository;
    private UserRepository userRepository;
    private ReviewAggregateRepository reviewAggregateRepository;
    private FavoriteRepository favoriteRepository;
    private StorageService storageService;
    private SubServiceRepository subServiceRepository;
    private ProfessionalSubServiceRepository professionalSubServiceRepository;
    private ProfessionalCoverageService professionalCoverageService;
    private ProfessionalsService professionalsService;
    private final AuthenticatedUser professionalCaller = new AuthenticatedUser(CALLER_ID, "PROFESSIONAL");

    @BeforeEach
    void setUp() {
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        reviewAggregateRepository = Mockito.mock(ReviewAggregateRepository.class);
        favoriteRepository = Mockito.mock(FavoriteRepository.class);
        storageService = Mockito.mock(StorageService.class);
        subServiceRepository = Mockito.mock(SubServiceRepository.class);
        professionalSubServiceRepository = Mockito.mock(ProfessionalSubServiceRepository.class);
        professionalCoverageService = Mockito.mock(ProfessionalCoverageService.class);
        // A real SubServiceSelectionValidator over the same mocked SubServiceRepository these
        // tests already stub: MS1 moved the existence/cross-category rule into it, and this suite
        // is where that rule's coverage lives, so it must stay exercised for real rather than
        // being mocked away.
        professionalsService = new ProfessionalsService(professionalRepository, userRepository,
                reviewAggregateRepository, favoriteRepository, storageService,
                new SubServiceSelectionValidator(subServiceRepository), professionalSubServiceRepository,
                professionalCoverageService, new SubServicePriceValidator(), subServiceRepository);
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
        when(reviewAggregateRepository.getRatingAggregate(PROFESSIONAL_ID))
                .thenReturn(new ProfessionalRatingAggregate(null, 0L));

        Professional professional = new Professional(CALLER_ID, SERVICE_REGION_ID, BASE_CITY_ID, BigDecimal.TEN);
        setField(professional, "id", PROFESSIONAL_ID);
        when(professionalRepository.findByUserId(CALLER_ID)).thenReturn(Optional.of(professional));
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

    private static SubService realSubService(Long id, Long categoryId) {
        try {
            var constructor = SubService.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            SubService subService = constructor.newInstance();
            setField(subService, "id", id);
            setField(subService, "categoryId", categoryId);
            setField(subService, "code", "code-" + id);
            setField(subService, "nameHe", "שם");
            setField(subService, "nameEn", "name");
            setField(subService, "displayOrder", (short) 1);
            return subService;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProfessionalSubService existingRow(Long professionalId, Long subServiceId) {
        ProfessionalSubService row = new ProfessionalSubService(professionalId, subServiceId);
        setField(row, "createdAt", java.time.Instant.now().minusSeconds(3600));
        return row;
    }

    @Test
    void updateProfessionalProfileRequest_hasNoForbiddenFields() {
        Set<String> forbidden = Set.of("id", "userId", "approvalStatus", "reliabilityScore",
                "averageRating", "reviewCount", "profileImageKey", "createdAt", "updatedAt");
        RecordComponent[] components = UpdateProfessionalProfileRequest.class.getRecordComponents();
        Set<String> actual = Arrays.stream(components).map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(actual).doesNotContainAnyElementsOf(forbidden);
        assertThat(actual).containsExactlyInAnyOrder("fullName", "serviceRegionId", "serviceCityIds",
                "baseCityId", "categoryIds", "bio", "basePrice");
    }

    @Test
    void updateMyProfile_roundTrip_leavesImmutableFieldsUnchanged() {
        Professional professional = new Professional(CALLER_ID, SERVICE_REGION_ID, BASE_CITY_ID, BigDecimal.ONE);
        setField(professional, "id", PROFESSIONAL_ID);
        setField(professional, "reliabilityScore", new BigDecimal("4.50"));
        String originalApprovalStatus = professional.getApprovalStatus();

        User user = new User("Old Name", "user@example.com", "hash", UserRole.PROFESSIONAL);
        setField(user, "id", CALLER_ID);

        when(professionalRepository.findByUserId(CALLER_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));
        when(professionalRepository.save(Mockito.any(Professional.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        // MS4: coverage and categories go through ProfessionalCoverageService, mocked here --
        // its own rules have their own tests (ProfessionalCoverageServiceTest); what this test is
        // about is which fields PUT /api/professionals/me is allowed to touch at all.
        when(professionalCoverageService.load(professional)).thenReturn(
                new ProfessionalCoverageService.CoverageView(NEW_REGION_ID, "חיפה והקריות", NEW_BASE_CITY_ID,
                        "חיפה", List.of(NEW_BASE_CITY_ID), List.of("חיפה"), List.of(CATEGORY_ID)));

        UpdateProfessionalProfileRequest request = new UpdateProfessionalProfileRequest(
                "New Name", NEW_REGION_ID, List.of(NEW_BASE_CITY_ID), NEW_BASE_CITY_ID, List.of(CATEGORY_ID),
                "New bio", new BigDecimal("250.00"));
        ProfessionalProfileResponse response = professionalsService.updateMyProfile(professionalCaller, request);

        // Allowlisted fields did change.
        assertThat(response.fullName()).isEqualTo("New Name");
        assertThat(response.serviceRegionId()).isEqualTo(NEW_REGION_ID);
        assertThat(response.city()).isEqualTo("חיפה");
        assertThat(response.bio()).isEqualTo("New bio");
        assertThat(response.basePrice()).isEqualByComparingTo("250.00");

        // The two MS4 relations are delegated, not written here.
        verify(professionalCoverageService).replaceCoverage(professional, NEW_REGION_ID,
                List.of(NEW_BASE_CITY_ID), NEW_BASE_CITY_ID, "");
        verify(professionalCoverageService).replaceCategories(PROFESSIONAL_ID, List.of(CATEGORY_ID), "categoryIds");

        // Non-allowlisted fields on the underlying entity are untouched by the request.
        assertThat(professional.getId()).isEqualTo(PROFESSIONAL_ID);
        assertThat(professional.getUserId()).isEqualTo(CALLER_ID);
        assertThat(professional.getApprovalStatus()).isEqualTo(originalApprovalStatus);
        assertThat(professional.getReliabilityScore()).isEqualByComparingTo("4.50");
        assertThat(response.id()).isEqualTo(PROFESSIONAL_ID);
        assertThat(response.categoryIds()).containsExactly(CATEGORY_ID);
        assertThat(response.approvalStatus()).isEqualTo(originalApprovalStatus);
    }

    // ---- sub-services (MS11, design §3.2) ----

    @Test
    void getMySubServices_returnsSelectedIds() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existingRow(PROFESSIONAL_ID, 101L), existingRow(PROFESSIONAL_ID, 102L)));
        // The response now carries each selection's catalogue label alongside its id, so the
        // sub_services rows it describes have to be readable.
        when(subServiceRepository.findAllById(any()))
                .thenReturn(List.of(realSubService(101L, CATEGORY_ID), realSubService(102L, CATEGORY_ID)));

        MySubServicesResponse response = professionalsService.getMySubServices(professionalCaller);

        assertThat(response.subServiceIds()).containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    void updateMySubServices_unknownId_returnsValidationError() {
        when(subServiceRepository.findAllById(any())).thenReturn(List.of());
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());

        UpdateSubServicesRequest request = new UpdateSubServicesRequest(List.of(999L), null);

        assertThatThrownBy(() -> professionalsService.updateMySubServices(professionalCaller, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(professionalSubServiceRepository, never()).save(any());
        verify(professionalSubServiceRepository, never()).deleteById(any());
    }

    @Test
    void updateMySubServices_categoryMismatch_returnsCategoryMismatch() {
        SubService wrongCategorySubService = realSubService(201L, OTHER_CATEGORY_ID);
        when(subServiceRepository.findAllById(any())).thenReturn(List.of(wrongCategorySubService));
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());

        UpdateSubServicesRequest request = new UpdateSubServicesRequest(List.of(201L), null);

        assertThatThrownBy(() -> professionalsService.updateMySubServices(professionalCaller, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.CATEGORY_MISMATCH));

        verify(professionalSubServiceRepository, never()).save(any());
        verify(professionalSubServiceRepository, never()).deleteById(any());
    }

    @Test
    void updateMySubServices_diffBased_onlyTouchesChangedRows() {
        // Caller currently has 101, 102 selected; requests 101 (kept), 103 (added) -- 102
        // should be removed, 101 should be left alone (its created_at is preserved because
        // it's never deleted/reinserted), 103 should be newly inserted.
        SubService s101 = realSubService(101L, CATEGORY_ID);
        SubService s103 = realSubService(103L, CATEGORY_ID);
        when(subServiceRepository.findAllById(any())).thenReturn(List.of(s101, s103));
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existingRow(PROFESSIONAL_ID, 101L), existingRow(PROFESSIONAL_ID, 102L)))
                .thenReturn(List.of(existingRow(PROFESSIONAL_ID, 101L), existingRow(PROFESSIONAL_ID, 103L)));

        UpdateSubServicesRequest request = new UpdateSubServicesRequest(List.of(101L, 103L), null);
        MySubServicesResponse response = professionalsService.updateMySubServices(professionalCaller, request);

        assertThat(response.subServiceIds()).containsExactlyInAnyOrder(101L, 103L);
        // Only the removed row (102) is deleted, only the newly-added row (103) is inserted --
        // 101 is neither deleted nor re-saved (diff-based, not delete-all-then-reinsert).
        verify(professionalSubServiceRepository, times(1)).deleteById(new ProfessionalSubServiceId(PROFESSIONAL_ID, 102L));
        verify(professionalSubServiceRepository, never()).deleteById(new ProfessionalSubServiceId(PROFESSIONAL_ID, 101L));
        verify(professionalSubServiceRepository, times(1))
                .save(Mockito.argThat(row -> row.getSubServiceId().equals(103L)));
        verify(professionalSubServiceRepository, never())
                .save(Mockito.argThat(row -> row.getSubServiceId().equals(101L)));
    }

    @Test
    void updateMySubServices_emptyList_savesEmptySelectionAndDeletesAllExisting() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existingRow(PROFESSIONAL_ID, 101L)))
                .thenReturn(List.of());

        UpdateSubServicesRequest request = new UpdateSubServicesRequest(List.of(), null);
        MySubServicesResponse response = professionalsService.updateMySubServices(professionalCaller, request);

        assertThat(response.subServiceIds()).isEmpty();
        verify(professionalSubServiceRepository, times(1)).deleteById(new ProfessionalSubServiceId(PROFESSIONAL_ID, 101L));
        verify(professionalSubServiceRepository, never()).save(any());
        // subServiceRepository is never even queried when the request has no ids to validate.
        verify(subServiceRepository, never()).findAllById(anyIterable());
    }

    // ---- MS1 (D-G): who is allowed to learn a professional's approval status ----

    private Professional rejectedProfessional() {
        Professional professional = new Professional(CALLER_ID, SERVICE_REGION_ID, BASE_CITY_ID, BigDecimal.TEN);
        setField(professional, "id", PROFESSIONAL_ID);
        professional.reject(7L, java.time.Instant.now(), "Verification document is illegible.");
        return professional;
    }

    private void stubProfileLookup(Professional professional) {
        User user = new User("Dana Cohen", "dana@example.com", "hash", UserRole.PROFESSIONAL);
        setField(user, "id", CALLER_ID);
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void getProfile_customerCaller_neverSeesApprovalStatus() {
        // Once the column carries a real decision, returning it to a browsing customer discloses
        // "this named person was rejected" to someone with no business knowing it.
        stubProfileLookup(rejectedProfessional());
        AuthenticatedUser customer = new AuthenticatedUser(999L, UserRole.CUSTOMER.name());
        when(favoriteRepository.existsByCustomerIdAndProfessionalId(999L, PROFESSIONAL_ID)).thenReturn(false);
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        ProfessionalProfileResponse response = professionalsService.getProfile(PROFESSIONAL_ID, customer);

        assertThat(response.approvalStatus()).isNull();
        // ...and the neutral replacement is what the UI actually needs: do not offer a booking.
        assertThat(response.bookable()).isFalse();
    }

    @Test
    void getProfile_otherProfessionalCaller_neverSeesApprovalStatus() {
        stubProfileLookup(rejectedProfessional());
        AuthenticatedUser otherProfessional = new AuthenticatedUser(888L, UserRole.PROFESSIONAL.name());
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        ProfessionalProfileResponse response = professionalsService.getProfile(PROFESSIONAL_ID, otherProfessional);

        assertThat(response.approvalStatus()).isNull();
    }

    @Test
    void getMyProfile_selfView_doesSeeApprovalStatus() {
        // The professional must be able to see where their own application stands.
        Professional professional = rejectedProfessional();
        User user = new User("Dana Cohen", "dana@example.com", "hash", UserRole.PROFESSIONAL);
        setField(user, "id", CALLER_ID);
        when(professionalRepository.findByUserId(CALLER_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        ProfessionalProfileResponse response = professionalsService.getMyProfile(professionalCaller);

        assertThat(response.approvalStatus()).isEqualTo(Professional.STATUS_REJECTED);
        assertThat(response.bookable()).isFalse();
    }

    @Test
    void getProfile_professionalViewingTheirOwnCardById_alsoSeesApprovalStatus() {
        // Self-view is decided from the row's own userId, not from which endpoint was called, so
        // the answer is the same whichever way the professional arrives at their own profile.
        stubProfileLookup(rejectedProfessional());
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        ProfessionalProfileResponse response =
                professionalsService.getProfile(PROFESSIONAL_ID, professionalCaller);

        assertThat(response.approvalStatus()).isEqualTo(Professional.STATUS_REJECTED);
    }

    @Test
    void getProfile_approvedButIncompleteOnboarding_isNotBookable() {
        // D4's core rule as the customer's client sees it: APPROVED is not the same as bookable,
        // and the flag the UI branches on is the eligibility answer, never the status.
        Professional professional = new Professional(CALLER_ID, SERVICE_REGION_ID, BASE_CITY_ID, BigDecimal.TEN);
        setField(professional, "id", PROFESSIONAL_ID);
        professional.approve(7L, java.time.Instant.now());
        stubProfileLookup(professional);
        AuthenticatedUser customer = new AuthenticatedUser(999L, UserRole.CUSTOMER.name());
        when(favoriteRepository.existsByCustomerIdAndProfessionalId(999L, PROFESSIONAL_ID)).thenReturn(false);
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        ProfessionalProfileResponse response = professionalsService.getProfile(PROFESSIONAL_ID, customer);

        assertThat(professional.getApprovalStatus()).isEqualTo(Professional.STATUS_APPROVED);
        assertThat(response.bookable()).isFalse();
    }

    @Test
    void getProfile_approvedAndCompleteOnboarding_isBookable() {
        Professional professional = new Professional(CALLER_ID, SERVICE_REGION_ID, BASE_CITY_ID, BigDecimal.TEN);
        setField(professional, "id", PROFESSIONAL_ID);
        professional.approve(7L, java.time.Instant.now());
        stubProfileLookup(professional);
        AuthenticatedUser customer = new AuthenticatedUser(999L, UserRole.CUSTOMER.name());
        when(favoriteRepository.existsByCustomerIdAndProfessionalId(999L, PROFESSIONAL_ID)).thenReturn(false);
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(true);

        ProfessionalProfileResponse response = professionalsService.getProfile(PROFESSIONAL_ID, customer);

        assertThat(response.bookable()).isTrue();
    }

    // ---- MS4: multiple categories and controlled service coverage on the profile surface ----

    @Test
    void getMyProfile_returnsEveryCategoryTheProfessionalServes_notJustOne() {
        Professional professional = professionalRepository.findByUserId(CALLER_ID).orElseThrow();
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(
                new User("Multi Pro", "multi@example.com", "hash", UserRole.PROFESSIONAL)));
        when(professionalCoverageService.load(professional)).thenReturn(
                new ProfessionalCoverageService.CoverageView(SERVICE_REGION_ID, "גוש דן", BASE_CITY_ID,
                        "תל אביב", List.of(BASE_CITY_ID, 41L), List.of("תל אביב", "רמת גן"),
                        List.of(1L, 8L)));

        ProfessionalProfileResponse response = professionalsService.getMyProfile(professionalCaller);

        assertThat(response.categoryIds()).containsExactly(1L, 8L);
        assertThat(response.serviceCityIds()).containsExactly(BASE_CITY_ID, 41L);
        assertThat(response.serviceCityNamesHe()).containsExactly("תל אביב", "רמת גן");
        assertThat(response.serviceRegionNameHe()).isEqualTo("גוש דן");
        assertThat(response.baseCityId()).isEqualTo(BASE_CITY_ID);
    }

    @Test
    void updateMyProfile_forACallerWithNoProfessionalRow_isForbidden_andWritesNothing() {
        // MS4 §18 widened this DTO to carry categories and coverage. The authorization rule it
        // has to keep is that the route only ever edits the caller's own row -- there is no
        // professionalId field to point somewhere else, and a caller who is not a professional
        // at all is refused before any of the new relations are touched.
        when(professionalRepository.findByUserId(CALLER_ID)).thenReturn(Optional.empty());

        UpdateProfessionalProfileRequest request = new UpdateProfessionalProfileRequest(
                "New Name", NEW_REGION_ID, List.of(NEW_BASE_CITY_ID), NEW_BASE_CITY_ID, List.of(CATEGORY_ID),
                "bio", new BigDecimal("250.00"));

        assertThatThrownBy(() -> professionalsService.updateMyProfile(professionalCaller, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));

        Mockito.verify(professionalCoverageService, Mockito.never())
                .replaceCoverage(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString());
        Mockito.verify(professionalCoverageService, Mockito.never())
                .replaceCategories(Mockito.anyLong(), Mockito.any(), Mockito.anyString());
        Mockito.verify(professionalRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void updateMySubServices_acceptsASubServiceUnderAnySelectedCategory() {
        // MS4: the professional serves two trades, and the sub-service belongs to the second.
        // Before MS4 this was a CATEGORY_MISMATCH; the whole point of the change is that it is
        // now legal, and that it is legal for exactly the categories they actually hold.
        Professional professional = professionalRepository.findByUserId(CALLER_ID).orElseThrow();
        when(professionalCoverageService.categoryIds(professional.getId()))
                .thenReturn(List.of(CATEGORY_ID, OTHER_CATEGORY_ID));
        when(subServiceRepository.findAllById(any())).thenReturn(List.of(realSubService(101L, OTHER_CATEGORY_ID)));
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());

        assertThatCode(() -> professionalsService.updateMySubServices(professionalCaller,
                new UpdateSubServicesRequest(List.of(101L), null)))
                .doesNotThrowAnyException();
    }

    @Test
    void updateMySubServices_stillRejectsACategoryTheProfessionalDoesNotServe() {
        Professional professional = professionalRepository.findByUserId(CALLER_ID).orElseThrow();
        when(professionalCoverageService.categoryIds(professional.getId())).thenReturn(List.of(CATEGORY_ID));
        when(subServiceRepository.findAllById(any())).thenReturn(List.of(realSubService(101L, OTHER_CATEGORY_ID)));

        assertThatThrownBy(() -> professionalsService.updateMySubServices(professionalCaller,
                new UpdateSubServicesRequest(List.of(101L), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.CATEGORY_MISMATCH));
    }
}
