package com.pronto.locations.dto;

import java.util.List;

/**
 * One canonical region and the cities inside it, on {@code GET /api/service-areas}. Shaped
 * exactly like {@code professionals.dto.CategoryWithSubServicesResponse} — parent plus nested
 * children in one response — so a client needs a single fetch to render both the region select
 * and the region-filtered city multi-select, and the two can never disagree about which cities
 * belong to which region.
 */
public record ServiceRegionResponse(
        Long id,
        String code,
        String nameHe,
        String nameEn,
        short displayOrder,
        List<ServiceCityResponse> cities
) {
}
