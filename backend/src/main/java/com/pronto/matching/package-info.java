/**
 * Distance/ETA approximation and "fastest" matching computation.
 *
 * <p>Pure computation only — no persistence, no controller, no {@code @Entity}/
 * {@code @Repository}. A {@link com.pronto.matching.DistanceEtaStrategy} implementation is
 * consumed by {@code bookings.service.BookingsService} to enrich each
 * {@code bookings.dto.ProfessionalCard} with distance/ETA figures and, when requested, to
 * re-sort a professional listing by fastest ETA. See {@code docs/architecture/overview.md}
 * §1 classification items 7-9 (distance/ETA/fastest-sort are all dynamically calculated,
 * never stored) for why this package owns no table and no migration.
 *
 * <p>{@link com.pronto.matching.ApproximateDistanceEtaStrategy} is a deliberately simple,
 * deterministic, no-real-routing-data placeholder — no GPS/live map integration exists in
 * v1.0 (out of scope per the project poster), so distance/ETA are coarse same-city/
 * different-city + peak/off-peak approximations, clearly documented as such at every
 * constant.
 */
package com.pronto.matching;
