package com.pronto.sos.service;

import com.pronto.sos.config.SosProperties;
import com.pronto.sos.entity.SosUrgency;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <b>How wide the search currently is.</b> One value object, derived from the request's expansion
 * count, that {@code SosMatchingService} and {@code SosDispatchService} both read instead of
 * reaching into {@link SosProperties} and re-deriving the same arithmetic in two places.
 *
 * <h2>What "expanded search scope" actually means in this implementation</h2>
 *
 * Two dimensions, and it is worth being precise about which one is real today:
 *
 * <ul>
 *   <li><b>{@link #poolSize} — the dimension that moves.</b> Matching scores every eligible
 *       professional in the category and truncates to a pool cap; only that many are ever
 *       contacted. Level 0 uses {@code candidate-pool-size} (or
 *       {@code emergency-candidate-pool-size} for an {@code EMERGENCY}); each expansion adds
 *       {@code expansion-pool-increment}. Since dispatch already excludes everybody previously
 *       offered and continues the same rank sequence, a wider pool means literally "we asked
 *       further down the ranked list of people who can do this job". That is a true statement
 *       about a real, bounded set, and it needs no data the platform does not have.</li>
 *   <li><b>{@link #maxRadiusKm} — the seam that is inert today.</b> Multiplied by
 *       {@code expansion-radius-multiplier} per level. The only distance implementation in this
 *       codebase ({@code matching.ApproximateDistanceEtaStrategy}) returns 8 km for a same-city
 *       professional and 35 km otherwise against a 40 km ceiling, so widening that ceiling
 *       currently changes nothing observable — and <b>no customer-facing copy quotes a radius or
 *       a distance</b>, precisely because it would be inventing precision the platform has not
 *       earned. Real geographic radius is a Production-roadmap milestone (Maps/GPS); this field
 *       is where it will land, so that swapping the strategy turns expansion into a genuine
 *       radius expansion without redesigning this flow.</li>
 * </ul>
 *
 * <p>Deliberately <b>not</b> a growing time window or a relaxed eligibility rule. Eligibility
 * (right category, SOS-available, approved, not soft-deleted) is a hard filter and stays one at
 * every level — a professional who should never have been asked does not become askable because
 * the customer pressed a button twice.
 *
 * @param level      how many expansions have been applied; {@code 0} is the initial scope
 * @param poolSize   the maximum number of professionals that may hold an offer on this request
 *                   in total, across every wave
 * @param maxRadiusKm the distance ceiling for this level, or {@code null} when the radius filter
 *                   is disabled entirely
 */
public record SosSearchScope(int level, int poolSize, BigDecimal maxRadiusKm) {

    /** The scope a brand-new request dispatches at. */
    public static SosSearchScope initial(SosUrgency urgency, SosProperties properties) {
        return forLevel(0, urgency, properties);
    }

    /**
     * The scope for a request that has been expanded {@code level} times.
     *
     * <p>{@code level} is read from {@code sos_requests.search_expansions}, which is canonical
     * backend state incremented only by an atomic compare-and-set — so two clients cannot
     * disagree about how wide the search is, and a replayed request re-derives exactly the same
     * scope.
     */
    public static SosSearchScope forLevel(int level, SosUrgency urgency, SosProperties properties) {
        int basePool = urgency == SosUrgency.EMERGENCY
                ? properties.getEmergencyCandidatePoolSize()
                : properties.getCandidatePoolSize();
        int poolSize = basePool + Math.max(0, level) * properties.getExpansionPoolIncrement();

        BigDecimal radius = properties.getMaxDispatchRadiusKm();
        if (radius != null && level > 0) {
            radius = radius.multiply(properties.getExpansionRadiusMultiplier().pow(level))
                    .setScale(1, RoundingMode.HALF_UP);
        }
        return new SosSearchScope(Math.max(0, level), poolSize, radius);
    }
}
