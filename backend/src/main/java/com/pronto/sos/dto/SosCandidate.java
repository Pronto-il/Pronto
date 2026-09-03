package com.pronto.sos.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One professional the customer may choose between, on
 * {@code GET /api/sos/requests/{id}/candidates}.
 *
 * <p>Carries everything the product brief says the customer should see before committing:
 * profile, rating, review volume, ETA, and a full price breakdown. The breakdown is itemized
 * rather than presented as one total on purpose — an SOS surcharge buried inside a single
 * number is exactly the kind of thing that erodes trust in an urgent moment, and the customer
 * is entitled to see what the visit costs versus what the urgency costs.
 *
 * <p>{@code totalVisitCost} is what the customer pays <b>for the visit</b>. It is deliberately
 * not "the price of the job": the cost of the actual repair is agreed between customer and
 * professional on site, and Pronto neither quotes nor takes a share of it.
 *
 * <p>{@code platformCommission} is Pronto's cut of {@code totalVisitCost} — included so the
 * figure is auditable rather than implicit, and because the same DTO shape backs the
 * professional's own view of what they will net.
 *
 * @param estimatedArrivalMinutes the professional's own committed ETA, given when they accepted.
 *                                <b>Always {@code null} while {@link #state} is
 *                                {@link SosCandidateState#REQUESTED}</b> — not by a rule applied
 *                                here, but structurally: {@code sos_offers.estimated_arrival_minutes}
 *                                is written by the acceptance statement and by nothing else, so
 *                                there is no path by which an unanswered offer carries a time. The
 *                                screen must not invent, estimate or borrow one.
 * @param offerId                 what the customer posts back to select this candidate — the
 *                                offer, not the professional, because the offer is what carries
 *                                the agreed price and ETA
 * @param state                   whether this professional has merely been asked or has actually
 *                                answered. See {@link SosCandidateState}; only
 *                                {@link SosCandidateState#ACCEPTED} may be selected, enforced
 *                                server-side in {@code SosService#selectProfessional} against the
 *                                offer's real status rather than against this field.
 * @param respondedAt             when they answered, or {@code null} for a
 *                                {@link SosCandidateState#REQUESTED} candidate — who by definition
 *                                has not. Was non-null on every candidate before REQUESTED
 *                                candidates became visible.
 */
public record SosCandidate(
        Long offerId,
        Long professionalId,
        SosCandidateState state,
        String fullName,
        String profileImageUrl,
        String city,
        String serviceRegion,
        BigDecimal averageRating,
        long reviewCount,
        Short estimatedArrivalMinutes,
        BigDecimal distanceKm,
        BigDecimal visitFee,
        BigDecimal sosFee,
        BigDecimal totalVisitCost,
        BigDecimal platformCommission,
        Instant respondedAt
) {
}
