package com.pronto.professionals.service;

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
        professionalsService = new ProfessionalsService(professionalRepository, userRepository,
                reviewAggregateRepository, favoriteRepository, storageService, subServiceRepository,
                professionalSubServiceRepository);
        when(reviewAggregateRepository.getRatingAggregate(PROFESSIONAL_ID))
                .thenReturn(new ProfessionalRatingAggregate(null, 0L));

        Professional professional = new Professional(CALLER_ID, CATEGORY_ID, "Tel Aviv", BigDecimal.TEN);
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
        Set<String> forbidden = Set.of("id", "userId", "categoryId", "approvalStatus", "reliabilityScore",
                "averageRating", "reviewCount", "profileImageKey", "createdAt", "updatedAt");
        RecordComponent[] components = UpdateProfessionalProfileRequest.class.getRecordComponents();
        Set<String> actual = Arrays.stream(components).map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(actual).doesNotContainAnyElementsOf(forbidden);
        assertThat(actual).containsExactlyInAnyOrder("fullName", "serviceArea", "city", "bio", "basePrice");
    }

    @Test
    void updateMyProfile_roundTrip_leavesImmutableFieldsUnchanged() {
        Professional professional = new Professional(CALLER_ID, CATEGORY_ID, "Old Area", BigDecimal.ONE);
        setField(professional, "id", PROFESSIONAL_ID);
        setField(professional, "reliabilityScore", new BigDecimal("4.50"));
        String originalApprovalStatus = professional.getApprovalStatus();

        User user = new User("Old Name", "user@example.com", "hash", UserRole.PROFESSIONAL);
        setField(user, "id", CALLER_ID);

        when(professionalRepository.findByUserId(CALLER_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));
        when(professionalRepository.save(Mockito.any(Professional.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProfessionalProfileRequest request = new UpdateProfessionalProfileRequest(
                "New Name", "New Area", "Haifa", "New bio", new BigDecimal("250.00"));
        ProfessionalProfileResponse response = professionalsService.updateMyProfile(professionalCaller, request);

        // Allowlisted fields did change.
        assertThat(response.fullName()).isEqualTo("New Name");
        assertThat(response.serviceArea()).isEqualTo("New Area");
        assertThat(response.city()).isEqualTo("Haifa");
        assertThat(response.bio()).isEqualTo("New bio");
        assertThat(response.basePrice()).isEqualByComparingTo("250.00");

        // Non-allowlisted fields on the underlying entity are untouched by the request.
        assertThat(professional.getId()).isEqualTo(PROFESSIONAL_ID);
        assertThat(professional.getUserId()).isEqualTo(CALLER_ID);
        assertThat(professional.getCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(professional.getApprovalStatus()).isEqualTo(originalApprovalStatus);
        assertThat(professional.getReliabilityScore()).isEqualByComparingTo("4.50");
        assertThat(response.id()).isEqualTo(PROFESSIONAL_ID);
        assertThat(response.categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(response.approvalStatus()).isEqualTo(originalApprovalStatus);
    }

    // ---- sub-services (MS11, design §3.2) ----

    @Test
    void getMySubServices_returnsSelectedIds() {
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(existingRow(PROFESSIONAL_ID, 101L), existingRow(PROFESSIONAL_ID, 102L)));

        MySubServicesResponse response = professionalsService.getMySubServices(professionalCaller);

        assertThat(response.subServiceIds()).containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    void updateMySubServices_unknownId_returnsValidationError() {
        when(subServiceRepository.findAllById(any())).thenReturn(List.of());
        when(professionalSubServiceRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of());

        UpdateSubServicesRequest request = new UpdateSubServicesRequest(List.of(999L));

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

        UpdateSubServicesRequest request = new UpdateSubServicesRequest(List.of(201L));

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

        UpdateSubServicesRequest request = new UpdateSubServicesRequest(List.of(101L, 103L));
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

        UpdateSubServicesRequest request = new UpdateSubServicesRequest(List.of());
        MySubServicesResponse response = professionalsService.updateMySubServices(professionalCaller, request);

        assertThat(response.subServiceIds()).isEmpty();
        verify(professionalSubServiceRepository, times(1)).deleteById(new ProfessionalSubServiceId(PROFESSIONAL_ID, 101L));
        verify(professionalSubServiceRepository, never()).save(any());
        // subServiceRepository is never even queried when the request has no ids to validate.
        verify(subServiceRepository, never()).findAllById(anyIterable());
    }
}
