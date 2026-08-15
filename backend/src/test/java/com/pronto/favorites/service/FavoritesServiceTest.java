package com.pronto.favorites.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.favorites.dto.AddFavoriteRequest;
import com.pronto.favorites.dto.FavoritesListResponse;
import com.pronto.favorites.entity.Favorite;
import com.pronto.favorites.repository.FavoriteRepository;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRatingAggregate;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.storage.client.StorageClient;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FavoritesService}: add/remove/list, idempotent duplicate-add,
 * ownership scoping (a customer-scoped delete can't touch another customer's favorite row).
 */
class FavoritesServiceTest {

    private static final Long CUSTOMER_ID = 10L;
    private static final Long OTHER_CUSTOMER_ID = 999L;
    private static final Long PROFESSIONAL_ID = 20L;

    private FavoriteRepository favoriteRepository;
    private ProfessionalRepository professionalRepository;
    private UserRepository userRepository;
    private ReviewAggregateRepository reviewAggregateRepository;
    private StorageClient storageClient;
    private FavoritesService favoritesService;
    private final AuthenticatedUser customer = new AuthenticatedUser(CUSTOMER_ID, "CUSTOMER");

    @BeforeEach
    void setUp() {
        favoriteRepository = Mockito.mock(FavoriteRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        reviewAggregateRepository = Mockito.mock(ReviewAggregateRepository.class);
        storageClient = Mockito.mock(StorageClient.class);
        favoritesService = new FavoritesService(favoriteRepository, professionalRepository, userRepository,
                reviewAggregateRepository, storageClient);
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

    private Professional professional() {
        Professional professional = new Professional(1L, 1L, "Tel Aviv", BigDecimal.TEN);
        setField(professional, "id", PROFESSIONAL_ID);
        setField(professional, "city", "Tel Aviv");
        return professional;
    }

    // ---- addFavorite ----

    @Test
    void addFavorite_happyPath_savesRow() {
        when(professionalRepository.existsById(PROFESSIONAL_ID)).thenReturn(true);
        when(favoriteRepository.existsByCustomerIdAndProfessionalId(CUSTOMER_ID, PROFESSIONAL_ID)).thenReturn(false);

        favoritesService.addFavorite(customer, new AddFavoriteRequest(PROFESSIONAL_ID));

        verify(favoriteRepository, times(1)).save(any(Favorite.class));
    }

    @Test
    void addFavorite_nonexistentProfessional_returnsValidationError() {
        when(professionalRepository.existsById(PROFESSIONAL_ID)).thenReturn(false);

        assertThatThrownBy(() -> favoritesService.addFavorite(customer, new AddFavoriteRequest(PROFESSIONAL_ID)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void addFavorite_alreadyFavorited_isIdempotentNotError() {
        when(professionalRepository.existsById(PROFESSIONAL_ID)).thenReturn(true);
        when(favoriteRepository.existsByCustomerIdAndProfessionalId(CUSTOMER_ID, PROFESSIONAL_ID)).thenReturn(true);

        favoritesService.addFavorite(customer, new AddFavoriteRequest(PROFESSIONAL_ID));

        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void addFavorite_uniqueConstraintRace_isTreatedAsSuccess() {
        when(professionalRepository.existsById(PROFESSIONAL_ID)).thenReturn(true);
        when(favoriteRepository.existsByCustomerIdAndProfessionalId(CUSTOMER_ID, PROFESSIONAL_ID)).thenReturn(false);
        when(favoriteRepository.save(any(Favorite.class))).thenThrow(new DataIntegrityViolationException("pk_favorites"));

        // Must not throw -- the race is swallowed, same as the pre-existing-row branch.
        favoritesService.addFavorite(customer, new AddFavoriteRequest(PROFESSIONAL_ID));
    }

    // ---- removeFavorite ----

    @Test
    void removeFavorite_delegatesToCustomerScopedDelete() {
        favoritesService.removeFavorite(customer, PROFESSIONAL_ID);

        verify(favoriteRepository, times(1)).deleteByCustomerIdAndProfessionalId(CUSTOMER_ID, PROFESSIONAL_ID);
    }

    @Test
    void removeFavorite_neverAffectsAnotherCustomersRow() {
        // The delete is always scoped to the caller's own id -- structurally cannot target
        // OTHER_CUSTOMER_ID's favorite row, regardless of which professionalId is passed.
        favoritesService.removeFavorite(customer, PROFESSIONAL_ID);

        verify(favoriteRepository, never()).deleteByCustomerIdAndProfessionalId(eq(OTHER_CUSTOMER_ID), any());
    }

    // ---- listFavorites ----

    @Test
    void listFavorites_returnsSummariesForOwnedFavorites() {
        Favorite favorite = new Favorite(CUSTOMER_ID, PROFESSIONAL_ID);
        when(favoriteRepository.findByCustomerIdOrderByCreatedAtDesc(CUSTOMER_ID)).thenReturn(List.of(favorite));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional()));
        when(userRepository.findById(1L)).thenReturn(Optional.of(
                new User("Dana Cohen", "dana@example.com", "hash", UserRole.PROFESSIONAL)));
        when(reviewAggregateRepository.getRatingAggregate(PROFESSIONAL_ID))
                .thenReturn(new ProfessionalRatingAggregate(4.5, 2L));

        FavoritesListResponse response = favoritesService.listFavorites(customer);

        assertThat(response.favorites()).hasSize(1);
        assertThat(response.favorites().get(0).professionalId()).isEqualTo(PROFESSIONAL_ID);
        assertThat(response.favorites().get(0).averageRating()).isEqualByComparingTo("4.50");
        assertThat(response.favorites().get(0).reviewCount()).isEqualTo(2);
    }

    @Test
    void listFavorites_emptyWhenNoFavorites() {
        when(favoriteRepository.findByCustomerIdOrderByCreatedAtDesc(CUSTOMER_ID)).thenReturn(List.of());

        FavoritesListResponse response = favoritesService.listFavorites(customer);

        assertThat(response.favorites()).isEmpty();
    }
}
