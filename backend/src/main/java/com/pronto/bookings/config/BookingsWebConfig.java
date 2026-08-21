package com.pronto.bookings.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers two separate, precisely-scoped {@link RoleRequiredInterceptor}s — one per
 * required role — rather than a single blanket {@code /api/bookings/**} pattern, because
 * this package's endpoints mix {@code CUSTOMER}-only, {@code PROFESSIONAL}-only, and
 * either-role routes (unlike {@code issues}/{@code storage}/{@code availability}, which each
 * needed only one role for every route). See
 * {@code docs/architecture/api-contract-bookings.md} §0.1 for the full rationale, including
 * why {@code /api/bookings/orders} must be registered as the bare literal path (matches only
 * {@code POST .../orders} itself, §2.4) rather than a {@code /**} wildcard, which would also
 * incorrectly swallow the either-role sub-paths ({@code /orders/{id}}, {@code /orders/me})
 * that must NOT be gated here.
 *
 * <p>As of the professional weekly availability calendar design (M2, §9.2.2), the literal
 * pattern {@code /api/bookings/professionals/*&#47;slots} is replaced by
 * {@code /api/bookings/professionals/*&#47;available-windows} — same {@code CUSTOMER}-only
 * gate, renamed route only (the old route is removed entirely, not kept for compatibility).
 *
 * <p>Milestone 4's two {@code CUSTOMER}-only SOS patterns
 * ({@code /api/bookings/sos-professionals}, {@code /api/bookings/sos-orders}) were removed
 * along with the browse-and-pick SOS flow itself — Pronto SOS ({@code /api/sos/**}, gated by
 * {@code sos.config.SosWebConfig}) is the only SOS flow now. Nothing in this package is
 * SOS-specific any more.
 *
 * <p>Milestone 6 adds two more {@code PROFESSIONAL}-only literal patterns
 * ({@code /api/bookings/orders/*&#47;on-the-way}, {@code /api/bookings/orders/*&#47;complete},
 * §2.16/§2.17) to that registration — same "literal-list doesn't pick up new routes
 * automatically" reasoning as the Milestone 4 addition above.
 *
 * <p>Nothing is registered for {@code cancel} (§2.7), {@code GET .../orders/{orderId}}
 * (§2.8), or {@code GET .../orders/me} (§2.9) — {@code auth.config.SecurityConfig}'s blanket
 * {@code .anyRequest().authenticated()} already guarantees any request reaching these routes
 * carries a valid JWT for some authenticated user (v1.0 has exactly two roles), and the real
 * per-resource authorization (is this caller a party to this specific order?) happens
 * entirely in {@code bookings.service.BookingsService} once the order is loaded.
 */
@Configuration
public class BookingsWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name()))
                .addPathPatterns("/api/bookings/professionals", "/api/bookings/professionals/*/available-windows",
                        "/api/bookings/orders");
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.PROFESSIONAL.name()))
                .addPathPatterns("/api/bookings/orders/*/accept", "/api/bookings/orders/*/reject",
                        "/api/bookings/orders/*/on-the-way", "/api/bookings/orders/*/complete");
    }
}
