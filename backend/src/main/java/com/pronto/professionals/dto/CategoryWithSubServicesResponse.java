package com.pronto.professionals.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/categories} -- a list of categories, each with its
 * nested {@code subServices}, both ordered by {@code display_order}. See {@code
 * docs/architecture/product-ms11-sub-services-design.md} §3.1.
 */
public record CategoryWithSubServicesResponse(
        Long id,
        String code,
        String nameHe,
        String nameEn,
        short displayOrder,
        List<SubServiceResponse> subServices
) {
}
