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
import com.pronto.storage.service.StorageService;
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
    private final StorageService storageService;

    public FavoritesService(FavoriteRepository favoriteRepository,
                             ProfessionalRepository professionalRepository,
                             UserRepository userRepository,
                             ReviewAggregateRepository reviewAggregateRepository,
                             StorageService storageService) {
        this.favoriteRepository = favoriteRepository;
        this.professionalRepository = professionalRepository;
        this.userRepository = userRepository;
        this.reviewAggregateRepository = reviewAggregateRepository;
        this.storageService = storageService;
    }

    /**
     * CUSTOMER only. {@code professionalId} must reference an existing professional
     * ({@code 400 VALIDATION_ERROR}, same convention
     * {@code bookings.dto.CreateOrderRequest}'s {@code professionalId} field-validation error
     * uses). Idempotent: insert, and a PK-violation race (already favorited) is treated as
     * success too — this endpoint always ends in "favorited," never an error for a caller who
     * just double-clicked.
     *
     * <p><b>MS1 (D-B):</b> the existence check becomes an eligibility check. Favoriting is a
     * creation path — it is how a customer builds the shortlist they will book from later — so an
     * unapproved or half-onboarded professional must not be addable to it, and the ineligible
     * professional's id must not become a way to confirm they exist. Note the deliberate
     * asymmetry with {@link #listFavorites}, which never deletes anything: adding is gated,
     * keeping is not.
     */
    @Transactional
    public void addFavorite(AuthenticatedUser caller, AddFavoriteRequest request) {
        if (!professionalRepository.existsEligibleById(request.professionalId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("professionalId",
                            "must reference an existing, bookable professional")));
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

    /**
     * CUSTOMER only.
     *
     * <p><b>MS1 (D-G):</b> a professional who has become ineligible stays in the list, carrying
     * {@code bookable = false}. Silently dropping them would be a worse answer to the same
     * question: the customer chose to save that person, the row is theirs, and a favorites list
     * that quietly shrinks reads as data loss rather than as "this one is not available right
     * now". Nothing here deletes a {@code favorites} row — an ineligible professional who
     * finishes onboarding simply becomes bookable again, with the customer's shortlist intact.
     */
    @Transactional(readOnly = true)
    public FavoritesListResponse listFavorites(AuthenticatedUser caller) {
        List<Favorite> favorites = favoriteRepository.findByCustomerIdOrderByCreatedAtDesc(caller.id());
        List<FavoriteProfessionalSummary> summaries = favorites.stream()
                .map(favorite -> toSummary(caller.id(), favorite))
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
    private FavoriteProfessionalSummary toSummary(Long callerId, Favorite favorite) {
        Professional professional = professionalRepository.findById(favorite.getProfessionalId()).orElse(null);
        if (professional == null) {
            return null;
        }
        User user = userRepository.findById(professional.getUserId()).orElse(null);
        String fullName = user == null ? null : user.getFullName();

        String profileImageUrl = professional.getProfileImageKey() == null
                ? null
                : storageService.getPresignedUrl(callerId, professional.getProfileImageKey());

        ProfessionalRatingAggregate aggregate = reviewAggregateRepository.getRatingAggregate(professional.getId());
        BigDecimal averageRating = aggregate.averageRating() == null
                ? null
                : BigDecimal.valueOf(aggregate.averageRating()).setScale(2, RoundingMode.HALF_UP);
        long reviewCount = aggregate.reviewCount() == null ? 0 : aggregate.reviewCount();

        return new FavoriteProfessionalSummary(professional.getId(), fullName, professional.getServiceArea(),
                professional.getCity(), professional.getBasePrice(), profileImageUrl, averageRating, reviewCount,
                favorite.getCreatedAt(), professionalRepository.existsEligibleById(professional.getId()));
    }
}
