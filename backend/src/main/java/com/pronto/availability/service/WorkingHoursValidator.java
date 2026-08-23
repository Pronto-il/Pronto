package com.pronto.availability.service;

import com.pronto.availability.dto.WorkingHoursItemRequest;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The weekly working-hours request rules, extracted in MS1 so
 * {@code PUT /api/availability/working-hours} and professional registration validate the exact
 * same week. Stateless and static — pure input validation with no collaborator, so registration
 * does not have to inject the whole {@code AvailabilityService} (seven collaborators, none of
 * which it needs) to reach it.
 *
 * <p>Rules unchanged from {@code AvailabilityService#updateWorkingHours}, where they lived
 * inline: exactly 7 entries, weekdays 0-6 each present exactly once, and
 * {@code startTime}/{@code endTime} required with {@code endTime > startTime} whenever
 * {@code enabled = true}. See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §4.2.
 */
public final class WorkingHoursValidator {

    private WorkingHoursValidator() {
    }

    /**
     * @throws ApiException {@code 400 VALIDATION_ERROR} on the first rule broken
     */
    public static void validateWeek(List<WorkingHoursItemRequest> items) {
        if (items == null || items.size() != 7) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("workingHours", "must contain exactly 7 entries, one per weekday 0-6")));
        }

        Set<Integer> seenWeekdays = new HashSet<>();
        for (WorkingHoursItemRequest item : items) {
            if (item.weekday() == null || item.weekday() < 0 || item.weekday() > 6) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                        List.of(new FieldError("weekday", "must be between 0 and 6")));
            }
            if (!seenWeekdays.add(item.weekday())) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                        List.of(new FieldError("weekday", "duplicate weekday " + item.weekday())));
            }
            if (item.enabled() == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                        List.of(new FieldError("enabled", "must not be null")));
            }
            if (item.enabled()) {
                if (item.startTime() == null || item.endTime() == null) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                            List.of(new FieldError("startTime", "startTime and endTime are required when enabled = true")));
                }
                if (!item.endTime().isAfter(item.startTime())) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                            List.of(new FieldError("endTime", "must be after startTime")));
                }
            }
        }
        // seenWeekdays now has exactly 7 unique values, each already range-checked to [0,6] --
        // structurally forces the set to be exactly {0,1,2,3,4,5,6}, so no separate gap check
        // is needed.
    }

    /**
     * At least one weekday is switched on.
     *
     * <p><b>Applied at registration only, deliberately not on the edit endpoint.</b> Onboarding is
     * incomplete without a bookable week (D4), so registration refuses an all-disabled submission.
     * An established professional switching every day off, on the other hand, is a professional
     * going on holiday — a legitimate thing to do that the platform must not block. They simply
     * stop being eligible until they switch a day back on, which
     * {@link com.pronto.professionals.ProfessionalEligibility} handles per query with no state to
     * repair.
     *
     * @param fieldPath the field name to report against — registration nests this payload
     */
    public static void requireAtLeastOneEnabledDay(List<WorkingHoursItemRequest> items, String fieldPath) {
        boolean anyEnabled = items != null && items.stream()
                .anyMatch(item -> Boolean.TRUE.equals(item.enabled()));
        if (!anyEnabled) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError(fieldPath, "at least one weekday must be enabled")));
        }
    }
}
