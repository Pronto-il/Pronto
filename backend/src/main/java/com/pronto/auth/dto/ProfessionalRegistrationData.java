package com.pronto.auth.dto;

import com.pronto.availability.dto.WorkingHoursItemRequest;

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
 * @param subServiceIds at least one, every one belonging to {@code categoryId}'s own category —
 *                      enforced by {@code professionals.service.SubServiceSelectionValidator},
 *                      the same component the later edit endpoint uses
 * @param workingHours  the full week, exactly 7 entries as
 *                      {@code PUT /api/availability/working-hours} takes them (the identical
 *                      record type, so the two surfaces cannot drift), with at least one enabled
 *                      day
 */
public record ProfessionalRegistrationData(
        Long categoryId,
        String serviceArea,
        BigDecimal basePrice,
        List<Long> subServiceIds,
        List<WorkingHoursItemRequest> workingHours
) {
}
