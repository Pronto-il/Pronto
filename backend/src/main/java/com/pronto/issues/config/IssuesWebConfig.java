package com.pronto.issues.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import com.pronto.auth.security.AuthRateLimitInterceptor;
import com.pronto.auth.security.ClientIpResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

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

    private final ClientIpResolver clientIpResolver;

    public IssuesWebConfig(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * {@code /api/issues/*}{@code /category} ({@code PATCH}, the customer's classification
     * correction) joins the two literal Milestone 2 paths. It is the one pattern here that is not
     * literal, because the path carries an id — but it is still precise: the single {@code *}
     * matches exactly one segment, so it covers {@code /api/issues/{id}/category} and nothing
     * else, and in particular it does not swallow the either-role {@code GET /api/issues/{id}}
     * that the Milestone 3 narrowing above deliberately left ungated.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /api/issues/classify is deliberately NOT here any more. Deferred authentication: a guest
        // must be able to describe a problem and have it classified before they have an account,
        // because requiring one first is the auth wall this change exists to move. Classification
        // writes no row, reads no other user's data, and returns a category for some text.
        //
        // The two that remain are writes: POST /api/issues creates a row owned by a customer, and
        // PATCH .../category edits one.
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name()))
                .addPathPatterns("/api/issues", "/api/issues/*/category");

        // Classification is now reachable without an account AND spends an OpenAI call on every
        // request, which is a combination nothing else in this API has. The per-IP limiter that
        // already protects the auth routes is the whole mitigation: same interceptor, same
        // ClientIpResolver (correct behind the ALB via TRUSTED_PROXIES), a bucket of its own.
        //
        // 20 per 10 minutes: a real customer reaching a category takes one call plus at most two
        // clarification rounds, so three. Twenty leaves room for restarts, second issues and a
        // shared household IP, while bounding what one source can spend to something that shows up
        // as a rate-limit graph rather than as a bill.
        registry.addInterceptor(new AuthRateLimitInterceptor(20, Duration.ofMinutes(10), clientIpResolver))
                .addPathPatterns("/api/issues/classify");
    }
}
