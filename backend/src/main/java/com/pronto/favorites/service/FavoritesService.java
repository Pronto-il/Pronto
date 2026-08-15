package com.pronto.favorites.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.favorites.dto.AddFavoriteRequest;
import com.pronto.favorites.dto.FavoriteProfessionalSummary;
import com.pronto.favorites.dto.FavoritesListResponse;
import com.pronto.favorites.entity.Favorite;
import com.pronto.favorites.repository.FavoriteRepository;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRatingAggregate;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.storage.client.StorageClient;
import com.pronto.users.entity.User;
import com.pronto.users.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * {@code POST}/{@code GET /api/favorites}, {@code DELETE /api/favorites/{professionalId}}.
 * Every route requires {@code CUSTOMER}, enforced by
 * {@code favorites.config.FavoritesWebConfig}.
 */
@Service
public class FavoritesService {

    private final FavoriteRepository favoriteRepository;
    private final ProfessionalRepository professionalRepository;
    private final UserRepository userRepository;
    private final ReviewAggregateRepository reviewAggregateRepository;
    private final StorageClient storageClient;

    public FavoritesService(FavoriteRepository favoriteRepository,
                             ProfessionalRepository professionalRepository,
                             UserRepository userRepository,
                             ReviewAggregateRepository reviewAggregateRepository,
                             StorageClient storageClient) {
        this.favoriteRepository = favoriteRepository;
        this.professionalRepository = professionalRepository;
        this.userRepository = userRepository;
        this.reviewAggregateRepository = reviewAggregateRepository;
        this.storageClient = storageClient;
    }

    /**
     * CUSTOMER only. {@code professionalId} must reference an existing professional
     * ({@code 400 VALIDATION_ERROR}, same convention
     * {@code bookings.dto.CreateOrderRequest}'s {@code professionalId} field-validation error
     * uses). Idempotent: insert, and a PK-violation race (already favorited) is treated as
     * success too — this endpoint always ends in "favorited," never an error for a caller who
     * just double-clicked.
     */
    @Transactional
    public void addFavorite(AuthenticatedUser caller, AddFavoriteRequest request) {
        if (!professionalRepository.existsById(request.professionalId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("professionalId", "must reference an existing professional")));
        }
        if (favoriteRepository.existsByCustomerIdAndProfessionalId(caller.id(), request.professionalId())) {
            return;
        }
        try {
            favoriteRepository.save(new Favorite(caller.id(), request.professionalId()));
        } catch (DataIntegrityViolationException e) {
            // Race backstop: another request already inserted the same (customerId,
            // professionalId) pair between the existence check above and this insert --
            // still a success (idempotent), same as the pre-existing-row branch above.
        }
    }

    /** CUSTOMER only. Idempotent delete — 204 regardless of whether the row existed. */
    @Transactional
    public void removeFavorite(AuthenticatedUser caller, Long professionalId) {
        favoriteRepository.deleteByCustomerIdAndProfessionalId(caller.id(), professionalId);
    }

    /** CUSTOMER only. */
    @Transactional(readOnly = true)
    public FavoritesListResponse listFavorites(AuthenticatedUser caller) {
        List<Favorite> favorites = favoriteRepository.findByCustomerIdOrderByCreatedAtDesc(caller.id());
        List<FavoriteProfessionalSummary> summaries = favorites.stream()
                .map(this::toSummary)
                .filter(Objects::nonNull)
                .toList();
        return new FavoritesListResponse(summaries);
    }

    /**
     * A favorited professional whose row was hard-deleted would already be gone from
     * {@code favorites} via {@code ON DELETE CASCADE} (see
     * {@code V17__create_favorites.sql}), so {@code findById} here is not expected to ever
     * miss — but resolved defensively (returns {@code null}, filtered out) rather than
     * throwing, since a listing endpoint shouldn't 500 over one stale row.
     */
    private FavoriteProfessionalSummary toSummary(Favorite favorite) {
        Professional professional = professionalRepository.findById(favorite.getProfessionalId()).orElse(null);
        if (professional == null) {
            return null;
        }
        User user = userRepository.findById(professional.getUserId()).orElse(null);
        String fullName = user == null ? null : user.getFullName();

        String profileImageUrl = professional.getProfileImageKey() == null
                ? null
                : storageClient.resolveUrl(professional.getProfileImageKey());

        ProfessionalRatingAggregate aggregate = reviewAggregateRepository.getRatingAggregate(professional.getId());
        BigDecimal averageRating = aggregate.averageRating() == null
                ? null
                : BigDecimal.valueOf(aggregate.averageRating()).setScale(2, RoundingMode.HALF_UP);
        long reviewCount = aggregate.reviewCount() == null ? 0 : aggregate.reviewCount();

        return new FavoriteProfessionalSummary(professional.getId(), fullName, professional.getServiceArea(),
                professional.getCity(), professional.getBasePrice(), profileImageUrl, averageRating, reviewCount,
                favorite.getCreatedAt());
    }
}
