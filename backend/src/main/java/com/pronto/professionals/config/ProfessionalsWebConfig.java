package com.pronto.professionals.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers a {@link RoleRequiredInterceptor} for the {@code PROFESSIONAL}-only {@code /me}
 * routes ({@code GET}/{@code PUT /api/professionals/me},
 * {@code POST /api/professionals/me/profile-image}, and, as of MS11 (Services &amp;
 * Sub-services), {@code GET}/{@code PUT /api/professionals/me/sub-services}). {@code GET
 * /api/professionals/{professionalId}} (either role) is left ungated — same "route-level
 * gate abstains" precedent as {@code issues.config.IssuesWebConfig}'s handling of its
 * either-role {@code GET /api/issues/{id}} route. {@code GET /api/categories} (MS11) is
 * public/unauthenticated and lives entirely outside this interceptor — it is not even a
 * {@code /api/professionals/*} route.
 *
 * <p>Literal patterns, not a blanket {@code /api/professionals/**}, for the same reason
 * {@code bookings.config.BookingsWebConfig}/{@code issues.config.IssuesWebConfig} use literal
 * lists — this package mixes a {@code PROFESSIONAL}-only surface with an either-role one.
 */
@Configuration
public class ProfessionalsWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.PROFESSIONAL.name()))
                .addPathPatterns("/api/professionals/me", "/api/professionals/me/profile-image",
                        "/api/professionals/me/sub-services");
    }
}
