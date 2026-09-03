package com.pronto.auth.dto;

import com.pronto.availability.dto.WorkingHoursItemRequest;
import com.pronto.professionals.dto.SubServicePriceSelection;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code professional}-role-specific registration payload, nested under
 * {@link RegisterRequest} iff {@code role == PROFESSIONAL}. Deliberately carries no Bean
 * Validation annotations, same reasoning as the flat DTO this replaced: these fields are
 * required *iff* {@code role == PROFESSIONAL}, which Bean Validation can't express
 * conditionally on a sibling field — validated in {@code AuthService} instead. See
 * backend registration flow separation task §7-10/§18-19.
 *
 * <p>The verification document and optional profile photo are NOT fields here — they're
 * uploaded as separate {@code multipart/form-data} parts on the same
 * {@code POST /api/auth/register} request (see {@code auth.controller.AuthController}),
 * since a canonical object-storage key can't exist yet for a Professional account that
 * hasn't been created — unlike {@code professionals.dto.UpdateProfessionalProfileRequest}'s
 * later self-service profile-image flow, which already has an authenticated caller.
 *
 * <p><b>MS1 (D4/D7):</b> {@code subServiceIds} and {@code workingHours} are new and both
 * required. MS0 recorded that registration created zero {@code professional_sub_services} rows
 * and zero {@code professional_working_hours} rows, so a professional who had just registered was
 * listed to customers while deriving an empty calendar — the customer discovered the dead end at
 * step 3 of 4. The fix is to collect the two missing pieces at the only moment the platform has
 * the registrant's attention, not to invent defaults for them: no working hours are fabricated
 * and no sub-services are guessed. Both stay editable afterwards through
 * {@code PUT /api/professionals/me/sub-services} and {@code PUT /api/availability/working-hours}.
 *
 * <p><b>MS4:</b> {@code categoryId} became {@link #categoryIds} — a professional may register as
 * a plumber <em>and</em> a handyman — and free-text {@code serviceArea} became the controlled
 * triple {@link #serviceRegionId}/{@link #serviceCityIds}/{@link #baseCityId}, validated against
 * the closed {@code service_regions}/{@code service_cities} catalogue by
 * {@code locations.service.ServiceCoverageValidator}. There is no field on this record a
 * registrant can put arbitrary place-name text into any more, which is the point: 'תל אביב',
 * 'תל-אביב' and 'Tel Aviv' used to be three different service areas.
 *
 * @param categoryIds   at least one, every one an existing {@code categories} row
 * @param subServiceIds at least one, every one belonging to one of {@link #categoryIds} —
 *                      enforced by {@code professionals.service.SubServiceSelectionValidator},
 *                      the same component the later edit endpoint uses. <b>The id-only form</b>,
 *                      kept working; supply {@link #subServices} instead to price them at
 *                      registration. Exactly one of the two is required, and {@link #subServices}
 *                      wins if both are sent — same rule, and the same reasoning, as
 *                      {@code professionals.dto.UpdateSubServicesRequest}
 * @param subServices   the priced form: each selected sub-service with what the professional
 *                      charges for it. The price is optional per entry (a professional may finish
 *                      registration and price their services later from the profile screen), but
 *                      when given it must be non-negative with at most two decimals
 * @param serviceCityIds at least one, every one inside {@link #serviceRegionId}
 * @param baseCityId    must be one of {@link #serviceCityIds}
 * @param workingHours  the full week, exactly 7 entries as
 *                      {@code PUT /api/availability/working-hours} takes them (the identical
 *                      record type, so the two surfaces cannot drift), with at least one enabled
 *                      day
 */
public record ProfessionalRegistrationData(
        List<Long> categoryIds,
        Long serviceRegionId,
        List<Long> serviceCityIds,
        Long baseCityId,
        BigDecimal basePrice,
        List<Long> subServiceIds,
        List<SubServicePriceSelection> subServices,
        List<WorkingHoursItemRequest> workingHours
) {

    /**
     * The selection normalised to the priced form, whichever shape the registrant sent — the single
     * accessor every consumer here uses, so validation and persistence cannot disagree about which
     * field was authoritative.
     */
    public List<SubServicePriceSelection> subServiceSelections() {
        if (subServices != null) {
            return subServices;
        }
        if (subServiceIds != null) {
            return subServiceIds.stream()
                    .map(id -> new SubServicePriceSelection(id, null))
                    .toList();
        }
        return List.of();
    }
}
