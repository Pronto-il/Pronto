package com.pronto.professionals.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for {@code PUT /api/professionals/me/sub-services} -- a full-replace of the
 * caller's entire sub-service selection, same shape precedent as {@code
 * availability.dto.WorkingHoursUpdateRequest}. Deliberately no {@code @NotEmpty} -- an empty
 * selection is a valid, un-blocking state (design doc §6 item 2, lead-approved). See {@code
 * docs/architecture/product-ms11-sub-services-design.md} §3.2.
 */
public record UpdateSubServicesRequest(
        @NotNull List<@NotNull Long> subServiceIds
) {
}
