package com.pronto.professionals.dto;

/**
 * One sub-service, nested inside {@link CategoryWithSubServicesResponse}. See {@code
 * docs/architecture/product-ms11-sub-services-design.md} §3.1.
 */
public record SubServiceResponse(
        Long id,
        String code,
        String nameHe,
        String nameEn,
        short displayOrder
) {
}
