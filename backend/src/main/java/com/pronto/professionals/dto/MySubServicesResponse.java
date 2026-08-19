package com.pronto.professionals.dto;

import java.util.List;

/**
 * Shared response shape for {@code GET}/{@code PUT /api/professionals/me/sub-services} --
 * deliberately only ids, not full sub-service objects (the frontend already has the full
 * catalog via {@code GET /api/categories}). See {@code
 * docs/architecture/product-ms11-sub-services-design.md} §3.2.
 */
public record MySubServicesResponse(List<Long> subServiceIds) {
}
