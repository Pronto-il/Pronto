package com.pronto.professionals.dto;

import java.util.List;

/**
 * Shared response shape for {@code GET}/{@code PUT /api/professionals/me/sub-services}. See {@code
 * docs/architecture/product-ms11-sub-services-design.md} §3.2.
 *
 * <p>{@link #subServiceIds} was originally the whole response — deliberately ids only, because the
 * frontend already has the full catalogue from {@code GET /api/categories}. It is <b>kept exactly as
 * it was</b> so existing clients and tests continue to work unchanged.
 *
 * <p>{@link #subServices} is the addition: the same selection, with each entry's price and its
 * Hebrew label. Both fields always describe the same set, in the same order (category display order,
 * then sub-service display order) — a client may read either, and must not have to reconcile them.
 */
public record MySubServicesResponse(
        List<Long> subServiceIds,
        List<MySubServiceItem> subServices
) {

    /** Builds both projections from the priced form, so the two can never disagree. */
    public static MySubServicesResponse of(List<MySubServiceItem> items) {
        return new MySubServicesResponse(items.stream().map(MySubServiceItem::subServiceId).toList(), items);
    }
}
