package com.pronto.sos.dto;

import com.pronto.sos.entity.SosUrgency;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Wire shape for {@code POST /api/sos/requests}.
 *
 * <p>Notably absent: {@code professionalId}. That is the entire point of Pronto SOS — the
 * customer names nobody and the platform dispatches. (The pre-existing browse-and-pick SOS
 * path, {@code POST /api/bookings/sos-orders}, still takes one.)
 *
 * <p>Also absent: category and description. Both come from the anchoring {@code issues} row,
 * which has already been through AI classification by the time SOS can be activated on it.
 * Accepting them again here would create two sources of truth for the same fact.
 *
 * <p>Bean Validation covers presence and shape only, matching this codebase's convention
 * ({@code CreateSosOrderRequest}); ownership, urgency-type and state checks happen in
 * {@code SosService}.
 *
 * @param issueSummary optional short headline shown on the professional's dispatch card
 * @param urgency      optional; defaults to {@link SosUrgency#URGENT}
 * @param latitude     optional, and not read by v1 matching — see {@code V34}'s column comment
 */
public record CreateSosRequestRequest(
        @NotNull @Positive Long issueId,
        @Size(max = 300) String issueSummary,
        SosUrgency urgency,
        @NotBlank @Size(max = 100) String serviceCity,
        @NotBlank @Size(max = 150) String serviceStreet,
        @NotBlank @Size(max = 20) String serviceHouseNumber,
        @Size(max = 20) String serviceApartment,
        @Size(max = 20) String serviceFloor,
        @Size(max = 20) String serviceEntrance,
        @Size(max = 500) String serviceAddressNotes,
        @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude
) {
}
