package com.pronto.professionals.service;

import com.pronto.common.security.AuthenticatedUser;
import com.pronto.favorites.repository.FavoriteRepository;
import com.pronto.professionals.dto.ProfessionalProfileResponse;
import com.pronto.professionals.dto.UpdateProfessionalProfileRequest;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRatingAggregate;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.storage.client.StorageClient;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProfessionalsService#updateMyProfile}'s allowlist behavior — confirms
 * {@link UpdateProfessionalProfileRequest} genuinely has no field for
 * {@code id}/{@code userId}/{@code categoryId}/{@code approvalStatus}/
 * {@code reliabilityScore}/rating/reviewCount/timestamps, and that a full round-trip update
 * leaves those fields unchanged on the underlying entity.
 */
class ProfessionalsServiceTest {

    private static final Long CALLER_ID = 1L;
    private static final Long PROFESSIONAL_ID = 50L;
    private static final Long CATEGORY_ID = 3L;

    private ProfessionalRepository professionalRepository;
    private UserRepository userRepository;
    private ReviewAggregateRepository reviewAggregateRepository;
    private FavoriteRepository favoriteRepository;
    private StorageClient storageClient;
    private ProfessionalsService professionalsService;
    private final AuthenticatedUser professionalCaller = new AuthenticatedUser(CALLER_ID, "PROFESSIONAL");

    @BeforeEach
    void setUp() {
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        reviewAggregateRepository = Mockito.mock(ReviewAggregateRepository.class);
        favoriteRepository = Mockito.mock(FavoriteRepository.class);
        storageClient = Mockito.mock(StorageClient.class);
        StorageService storageService = Mockito.mock(StorageService.class);
        professionalsService = new ProfessionalsService(professionalRepository, userRepository,
                reviewAggregateRepository, favoriteRepository, storageClient, storageService);
        when(reviewAggregateRepository.getRatingAggregate(PROFESSIONAL_ID))
                .thenReturn(new ProfessionalRatingAggregate(null, 0L));
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
}
