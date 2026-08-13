package com.pronto.issues.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers a {@link RoleRequiredInterceptor} for the two Milestone 2 {@code /api/issues/*}
 * routes that require {@code role = CUSTOMER} (Milestone 2 §0.1), enforced before Spring
 * resolves {@code @Valid} request bodies — see {@link RoleRequiredInterceptor}'s javadoc for
 * the full ordering-bug rationale this fixes.
 *
 * <p><b>Narrowed, Milestone 3 (2026-08-13):</b> originally registered on the blanket pattern
 * {@code /api/issues/**}, which incorrectly {@code 403}'d a {@code PROFESSIONAL} caller
 * hitting the new {@code GET /api/issues/{id}} (§2.1), an either-role route — a real,
 * previously-unflagged conflict caught by
 * {@code docs/architecture/api-contract-bookings.md} §0.1's finalization-pass sanity check.
 * Now scoped to only the two literal Milestone 2 paths that actually need
 * {@code CUSTOMER}-only restriction; {@code GET /api/issues/{id}} has no interceptor
 * registered for it at all — {@code auth.config.SecurityConfig}'s blanket
 * {@code .anyRequest().authenticated()} already covers "some authenticated role," and the
 * real ownership check happens in {@code IssuesService.getById} (same "route-level gate
 * abstains, service layer authorizes" pattern used throughout {@code bookings}).
 *
 * <p>Deliberately lives here, not in {@code common} — {@code common} must never depend on a
 * domain package or hard-code which routes require which role (see {@code common/README.md});
 * each domain package instead registers its own required role for its own routes, keeping
 * {@link RoleRequiredInterceptor} itself generic/reusable infrastructure.
 *
 * <p>Any future {@code CUSTOMER}-only endpoint added to {@code IssuesController} needs its
 * own literal path added here (no longer automatic under a blanket pattern) — an accepted
 * trade-off now that this package mixes a {@code CUSTOMER}-only surface with an either-role
 * one, mirroring why {@code bookings.config.BookingsWebConfig} also uses precise literal
 * patterns rather than a wildcard.
 */
@Configuration
public class IssuesWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name()))
                .addPathPatterns("/api/issues/classify", "/api/issues");
    }
}
