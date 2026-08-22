package com.pronto.sos.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Route-level role gating for {@code /api/sos/**}, following
 * {@code bookings.config.BookingsWebConfig}'s pattern exactly: two precisely-scoped
 * {@link RoleRequiredInterceptor} registrations rather than one blanket pattern, because this
 * package mixes {@code CUSTOMER}-only, {@code PROFESSIONAL}-only, and either-role routes.
 *
 * <p><b>Literal paths, deliberately not wildcards.</b> {@code /api/sos/requests} is registered
 * as the bare literal so it matches only {@code POST .../requests} itself. A
 * {@code /api/sos/requests/**} pattern would also swallow {@code GET .../requests/{id}},
 * {@code GET .../requests/me}, {@code GET .../requests/{id}/events} and the professional-only
 * operational transitions — all of which must not carry a {@code CUSTOMER} gate. The cost of
 * this choice is that new routes are not picked up automatically and must be added here
 * explicitly; that is the same tradeoff {@code bookings} already made, and the safer direction
 * to fail in (a forgotten route is over-permissive at the route level but still fully
 * authorized in the service layer, never under-permissive and silently broken).
 *
 * <p><b>Ungated routes</b> — {@code GET .../requests/{id}}, {@code GET .../requests/me},
 * {@code GET .../requests/{id}/events}, {@code POST .../requests/{id}/cancel} — are genuinely
 * either-role. {@code auth.config.SecurityConfig}'s blanket
 * {@code .anyRequest().authenticated()} already guarantees a valid JWT, and the real question
 * ("are you a party to this specific request?") is answered in {@code SosService} once the row
 * is loaded, which is the only place it can be answered.
 */
@Configuration
public class SosWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name()))
                .addPathPatterns(
                        "/api/sos/requests",
                        "/api/sos/requests/*/candidates",
                        "/api/sos/requests/*/scan-again",
                        "/api/sos/requests/*/select");

        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.PROFESSIONAL.name()))
                .addPathPatterns(
                        "/api/sos/offers",
                        "/api/sos/offers/*",
                        "/api/sos/offers/*/accept",
                        "/api/sos/offers/*/reject",
                        "/api/sos/offers/*/eta",
                        "/api/sos/requests/*/confirm",
                        "/api/sos/requests/*/on-the-way",
                        "/api/sos/requests/*/arrived",
                        "/api/sos/requests/*/complete");
    }
}
