package com.pronto.sos.dto;

import com.pronto.sos.entity.SosOfferStatus;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.entity.SosUrgency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The professional's view of one SOS opportunity — {@code GET /api/sos/offers} and
 * {@code GET /api/sos/offers/{id}}, and the response to accept/reject/ETA updates.
 *
 * <p>Deliberately carries the request's <em>context</em> (category, summary, city, urgency,
 * status) inline rather than making the professional fetch the request separately: they are
 * deciding in seconds, and a second round trip before the decision screen can render is exactly
 * the wrong tradeoff here.
 *
 * <p><b>Address disclosure.</b> {@code serviceStreet} and {@code serviceCity} are exposed at
 * offer time — enough to estimate a realistic arrival time, which is the whole point of asking
 * for one. The house number, apartment, floor, entrance, address notes and coordinates are
 * withheld until the professional is actually selected, and then served via the order: an
 * accepted-but-not-selected professional has no business knowing which door to knock on, and
 * offers go out to up to 15 people. See {@code sos.service.SosAddressAccess}, which is the one
 * place that rule is defined.
 *
 * <p>The money fields are the offer's snapshots, plus {@code professionalNet} — what they keep
 * after Pronto's commission — computed rather than stored, so the professional never has to do
 * the arithmetic to know what accepting is worth.
 *
 * <p><b>What the professional is actually being asked to judge</b> — {@code issueDescription} and
 * {@code issuePhotos} — travels here too, and its absence was a real defect: this DTO carried only
 * {@code issueSummary}, an OPTIONAL headline that the app never sends when creating an SOS request,
 * so in practice a professional was offered an emergency described to them as a street name. The
 * description is the customer's own words, never an AI summary, and the photos are the ones
 * attached to the anchoring issue.
 *
 * <p>Both are disclosed at offer time, unlike the house number, and the distinction is the one
 * {@code sos.service.SosAddressAccess} draws: a photo of a burst pipe identifies a FAULT, which is
 * the thing being accepted or declined; a house number identifies a DOOR, which is what selection
 * grants. Withholding the fault until after acceptance would be asking somebody to commit blind.
 */
public record SosOfferResponse(
        Long id,
        Long sosRequestId,
        Long professionalId,
        SosOfferStatus status,
        SosRequestStatus requestStatus,
        Long categoryId,
        String issueSummary,
        /** The customer's own description of the problem, verbatim. {@code null} if the anchoring
         *  issue could not be read. Never an AI summary — see {@code issues.entity.Issue}. */
        String issueDescription,
        /** Every photo attached to the issue, each with a freshly-minted presigned URL. Empty when
         *  the customer attached none; never {@code null}. */
        List<SosIssuePhoto> issuePhotos,
        SosUrgency urgency,
        String serviceCity,
        String serviceStreet,
        Short matchRank,
        BigDecimal distanceKm,
        Short estimatedArrivalMinutes,
        BigDecimal visitFee,
        BigDecimal sosFee,
        BigDecimal platformCommission,
        BigDecimal professionalNet,
        Long orderId,
        Instant offeredAt,
        Instant viewedAt,
        Instant respondedAt,
        Instant expiresAt
) {
}
