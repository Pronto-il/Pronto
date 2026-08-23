package com.pronto.issues.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Wire shape for {@code PATCH /api/issues/{id}/category}.
 *
 * <p>Deliberately a single field. The customer correcting Pronto's classification is the one
 * thing about an already-created issue that this API lets them change, and a
 * {@code categoryId}-only body is what keeps that true: there is no shape here into which a
 * description, an urgency or a status could be smuggled. The narrow path
 * ({@code /{id}/category}, not {@code PATCH /api/issues/{id}}) says the same thing at the route
 * level.
 *
 * <p>The referenced category is checked against the real {@code categories} table server-side,
 * exactly as {@link CreateIssueRequest}'s is — a bean-validation annotation cannot do that.
 */
public record UpdateIssueCategoryRequest(
        @NotNull Long categoryId
) {
}
