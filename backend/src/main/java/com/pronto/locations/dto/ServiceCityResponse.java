package com.pronto.locations.dto;

/**
 * One canonical city on {@code GET /api/service-areas}. {@code id} is what clients persist;
 * {@code nameHe} is what they display. {@code code} is the stable machine-readable handle for
 * anything that needs to name a specific city without hardcoding a serial id.
 */
public record ServiceCityResponse(
        Long id,
        String code,
        String nameHe,
        String nameEn,
        short displayOrder
) {
}
