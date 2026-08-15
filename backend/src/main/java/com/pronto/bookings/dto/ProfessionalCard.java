package com.pronto.bookings.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One entry in {@code GET /api/bookings/professionals?issueId=}'s (and
 * {@code .../sos-professionals}'s) {@code professionals} array. See
 * {@code docs/architecture/api-contract-bookings.md} §2.2. {@code reliabilityScore} may be
 * {@code null} (no computation mechanism exists yet).
 *
 * <p><b>Two-stage construction, purely additive on top of the original 5 fields.</b>
 * {@code ProfessionalListingRepository}'s {@code SELECT NEW} JPQL constructor expressions use
 * the {@linkplain #ProfessionalCard(Long, String, String, BigDecimal, BigDecimal, String,
 * String, Double, Long, Long) JPQL-projection constructor} below, which accepts the raw
 * scalar-subquery types JPQL naturally produces (nullable {@code Double}/{@code Long}) and
 * converts/defaults them into this record's canonical types. At that stage,
 * {@code profileImageUrl} actually carries the raw {@code professionals.profile_image_key}
 * column value (not yet a resolved URL) and the {@code sameCity}/{@code distanceKm}/
 * {@code baseTravelTimeMinutes}/{@code trafficAdjustmentMinutes}/{@code etaMinutes} fields are
 * all placeholders — {@code bookings.service.BookingsService} performs a second, in-Java-only
 * enrichment pass per card (resolving the image URL via {@code StorageClient}, and computing
 * ETA/distance via {@code matching.DistanceEtaStrategy}, never in SQL — per the approved
 * design), producing the final card via the canonical constructor.
 */
public record ProfessionalCard(
        Long professionalId,
        String fullName,
        String serviceArea,
        BigDecimal basePrice,
        BigDecimal reliabilityScore,
        String city,
        String profileImageUrl,
        BigDecimal averageRating,
        long reviewCount,
        boolean favorited,
        boolean sameCity,
        BigDecimal distanceKm,
        int baseTravelTimeMinutes,
        int trafficAdjustmentMinutes,
        int etaMinutes
) {

    /**
     * JPQL-projection constructor, used exclusively by
     * {@code bookings.repository.ProfessionalListingRepository}'s {@code SELECT NEW}
     * expressions. {@code averageRating}/{@code reviewCount} come from correlated {@code AVG}/
     * {@code COUNT} subqueries over {@code reviews} ({@code null}/{@code 0} when the
     * professional has no reviews yet); {@code favoritedCount} comes from a correlated
     * {@code COUNT} subquery over {@code favorites} scoped to the calling customer (0 or 1,
     * never more — {@code favorites}' composite PK guarantees at most one row per
     * (customer, professional) pair), converted to a plain {@code boolean} here.
     */
    public ProfessionalCard(Long professionalId, String fullName, String serviceArea, BigDecimal basePrice,
                             BigDecimal reliabilityScore, String city, String profileImageKey,
                             Double averageRating, Long reviewCount, Long favoritedCount) {
        this(professionalId, fullName, serviceArea, basePrice, reliabilityScore, city, profileImageKey,
                averageRating == null ? null : BigDecimal.valueOf(averageRating).setScale(2, RoundingMode.HALF_UP),
                reviewCount == null ? 0L : reviewCount,
                favoritedCount != null && favoritedCount > 0,
                false, BigDecimal.ZERO, 0, 0, 0);
    }
}
