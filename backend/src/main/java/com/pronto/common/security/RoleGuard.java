package com.pronto.common.security;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;

/**
 * Role-restriction check shared by every Milestone 2 endpoint (all of
 * {@code issues}/{@code storage} require {@code role = CUSTOMER}, per
 * {@code docs/architecture/api-contract-issues.md} §0.1).
 *
 * <p><b>Deliberately not implemented as {@code @PreAuthorize}/method security.</b> Wiring
 * {@code @EnableMethodSecurity} plus a custom {@code AccessDeniedHandler} (so a denial
 * still produces the standard error envelope, not Spring Security's default blank 403)
 * would require changes to {@code auth.config.SecurityConfig} — out of bounds for this
 * task (the {@code auth} package is explicitly not to be touched). Flagged to
 * {@code pronto-lead}: if a later milestone is allowed to touch {@code SecurityConfig},
 * migrating this to declarative {@code @PreAuthorize} is a reasonable follow-up, not
 * required.
 *
 * <p><b>Call site (updated):</b> {@link #requireRole} is called by
 * {@link RoleRequiredInterceptor#preHandle}, registered per-route by
 * {@code issues.config.IssuesWebConfig}/{@code storage.config.StorageWebConfig}, rather than
 * as the first line of each controller method body. It was originally called directly from
 * controller method bodies, but Spring resolves {@code @Valid}/{@code @RequestParam}
 * argument binding for the matched handler method *before* the method body runs — so a
 * wrong-role request with an also-malformed body incorrectly surfaced as
 * {@code 400 VALIDATION_ERROR} instead of {@code 403 FORBIDDEN}. Moving the
 * {@link #requireRole} call into a {@code HandlerInterceptor} (which runs before argument
 * resolution) fixed that ordering bug without touching {@code SecurityConfig}. This method
 * itself is unchanged and still reuses the exact same JWT {@code role} claim and the exact
 * same {@link ApiException}/{@code GlobalExceptionHandler} envelope mechanism every other
 * error in the app goes through — not a parallel mechanism, just invoked one phase earlier
 * now.
 */
public final class RoleGuard {

    private RoleGuard() {
    }

    /**
     * @throws ApiException {@code 403 FORBIDDEN} if {@code principal} is missing or its
     *                       role doesn't match {@code requiredRole}.
     */
    public static void requireRole(AuthenticatedUser principal, String requiredRole) {
        if (principal == null || !requiredRole.equals(principal.role())) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "This action requires role " + requiredRole + ".");
        }
    }
}
