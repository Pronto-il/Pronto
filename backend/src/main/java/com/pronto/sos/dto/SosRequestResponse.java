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
 * <p>{@code selectionExpiresAt} and {@code matchingExpiresAt} are absolute instants, not
 * remaining-second counts, deliberately: the backend is the source of truth for these deadlines
 * and a client that renders a countdown from an absolute deadline stays correct across a slow
 * response or a backgrounded tab, whereas one handed "120 seconds left" does not. The server
 * enforces the deadline regardless of what any client displays.
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
        Long orderId,
        SosActorType cancelledBy,
        int offerCount,
        int acceptedCandidateCount,
        Instant matchingExpiresAt,
        Instant selectionExpiresAt,
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
