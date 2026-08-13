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
 * <p>Milestone 4 adds two more {@code CUSTOMER}-only literal patterns
 * ({@code /api/bookings/sos-professionals}, {@code /api/bookings/sos-orders}, §2.12/§2.13) to
 * the same registration — this package's literal-list design doesn't pick up new routes
 * automatically the way a wildcard would (§0.1/§6 item 8 of the contract doc).
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
                .addPathPatterns("/api/bookings/professionals", "/api/bookings/professionals/*/slots",
                        "/api/bookings/orders", "/api/bookings/sos-professionals", "/api/bookings/sos-orders");
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.PROFESSIONAL.name()))
                .addPathPatterns("/api/bookings/orders/*/accept", "/api/bookings/orders/*/reject");
    }
}
