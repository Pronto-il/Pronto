/**
 * Shared exceptions, the global error-response envelope, common DTOs, and the
 * cross-request security principal type.
 *
 * <p>Cross-cutting home for code used by more than one domain package — the global
 * {@code @RestControllerAdvice} exception handler and error envelope
 * ({@code docs/architecture/api-contract.md} §1), and {@code security.AuthenticatedUser},
 * the JWT-derived {@code Authentication} principal read via
 * {@code @AuthenticationPrincipal} by any authenticated endpoint. Deliberately has zero
 * dependencies on any domain package (see {@code common/README.md}).
 *
 * <p>Populated in Milestone 1 per {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.common;
