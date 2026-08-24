package com.pronto.sos.dto;

import com.pronto.sos.entity.SosActorType;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.entity.SosUrgency;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The canonical SOS request shape, returned by create, get, cancel and every professional-side
 * operational transition.
 *
 * <p>{@code matchingExpiresAt} — when active scanning stops — is an absolute instant, not a
 * remaining-second count, deliberately: the backend is the source of truth and a client that
 * renders from an absolute deadline stays correct across a slow response or a backgrounded tab,
 * whereas one handed "120 seconds left" does not.
 *
 * <p><b>There is no customer-decision deadline on this DTO, because there is no such deadline.</b>
 * A {@code selectionExpiresAt} was removed in the MS3 follow-up along with the rule it expressed:
 * a professional who has committed to come stays selectable until the customer chooses, cancels,
 * or every offer has lapsed with nothing accepted. Whether choosing is possible right now is
 * simply {@code status == WAITING_FOR_CUSTOMER_SELECTION}.
 *
 * @param acceptedCandidateCount how many professionals have accepted so far — what drives the
 *                               customer's "finding you a professional… 2 found" progress view
 *                               without exposing who they are before selection opens
 */
public record SosRequestResponse(
        Long id,
        Long issueId,
        Long customerId,
        Long categoryId,
        Long subServiceId,
        String issueSummary,
        SosUrgency urgency,
        SosRequestStatus status,
        String serviceCity,
        String serviceStreet,
        String serviceHouseNumber,
        String serviceApartment,
        String serviceFloor,
        String serviceEntrance,
        String serviceAddressNotes,
        BigDecimal latitude,
        BigDecimal longitude,
        Long selectedProfessionalId,
        String selectedProfessionalName,
        Long selectedOfferId,
        /**
         * The selected professional's own committed ETA, in minutes, kept current as they revise
         * it. {@code null} until somebody is selected.
         *
         * <p>Here rather than left to the candidates endpoint because that endpoint returns only
         * {@code ACCEPTED} offers and so goes empty the moment a choice is made — the customer's
         * tracking screen was therefore rendering the ETA from a snapshot taken before selection,
         * and a post-selection revision could never reach it however promptly the client
         * refetched. The number the customer is watching has to live on the resource that stays
         * canonical for the whole journey.
         */
        Short selectedEstimatedArrivalMinutes,
        Long orderId,
        SosActorType cancelledBy,
        int offerCount,
        int acceptedCandidateCount,
        /**
         * How many times the customer has widened this search with "סרוק שוב". {@code 0} for a
         * request still running at its initial scope.
         */
        int searchExpansions,
        /**
         * The configured ceiling ({@code pronto.sos.max-search-expansions}). Sent so the client
         * can say "this is as wide as it gets" without hardcoding the platform's own bound, and
         * so raising it is a configuration change rather than a frontend release.
         */
        int maxSearchExpansions,
        /**
         * <b>Whether "סרוק שוב" would be accepted right now</b> — the same three conditions the
         * guarded update in {@code SosRequestRepository#expandSearch} enforces, evaluated
         * server-side so the control's enabled state is canonical backend state rather than a
         * client's guess at the rules. Goes false the instant a professional is selected, which
         * is what removes the button as part of "selection stops the search".
         */
        boolean canExpandSearch,
        Instant matchingExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant matchedAt,
        Instant candidatesReadyAt,
        Instant selectedAt,
        Instant confirmedAt,
        Instant cancelledAt,
        Instant completedAt
) {
}
