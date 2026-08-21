package com.pronto.ai.catalog;

/**
 * A real {@code categories} row, paired with its authored routing boundary (may be
 * {@code null} for a category no profile has been written for yet). Detached, immutable view
 * so the prompt/decision layers never touch the JPA entity or the repository directly.
 */
public record ServiceCategory(Long id, String code, String nameHe, String nameEn,
                               CategoryRoutingProfile routingProfile) {
}
