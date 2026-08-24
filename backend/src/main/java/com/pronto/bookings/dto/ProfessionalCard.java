package com.pronto.bookings.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

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
 * enrichment pass per card (resolving the image URL via {@code StorageClient}, computing
 * ETA/distance via {@code matching.DistanceEtaStrategy}, never in SQL — per the approved
 * design — and attaching {@code categoryIds}), producing the final card via the canonical
 * constructor.
 *
 * <p><b>MS4.</b> {@code serviceArea} became {@link #serviceRegion}: the Hebrew label of the
 * professional's canonical {@code service_regions} row, joined in, rather than whatever free
 * text they once typed. {@link #city} is unchanged in type and meaning — it is still the one
 * city ETA is measured from — but it is now the joined label of {@code professionals
 * .base_city_id} rather than a free-text column, so 'תל אביב' and 'תל-אביב' can no longer be two
 * different places. Both are nullable, for pre-MS4 rows {@code V44} could not canonicalise.
 * {@link #categoryIds} is new: a professional may serve several trades, and the card has to be
 * able to say so.
 */
public record ProfessionalCard(
        Long professionalId,
        String fullName,
        String serviceRegion,
        BigDecimal basePrice,
        BigDecimal reliabilityScore,
        String city,
        String profileImageUrl,
        BigDecimal averageRating,
        long reviewCount,
        boolean favorited,
        List<Long> categoryIds,
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
     *
     * <p>{@code categoryIds} starts empty and is filled by the same enrichment pass, from one
     * batched {@code professional_categories} lookup for the whole page rather than a correlated
     * subquery per card — JPQL cannot project a collection into a constructor expression, and
     * N+1 queries for a listing is not a trade worth making to pretend otherwise.
     */
    public ProfessionalCard(Long professionalId, String fullName, String serviceRegion, BigDecimal basePrice,
                             BigDecimal reliabilityScore, String city, String profileImageKey,
                             Double averageRating, Long reviewCount, Long favoritedCount) {
        this(professionalId, fullName, serviceRegion, basePrice, reliabilityScore, city, profileImageKey,
                averageRating == null ? null : BigDecimal.valueOf(averageRating).setScale(2, RoundingMode.HALF_UP),
                reviewCount == null ? 0L : reviewCount,
                favoritedCount != null && favoritedCount > 0,
                List.of(), false, BigDecimal.ZERO, 0, 0, 0);
    }
}
