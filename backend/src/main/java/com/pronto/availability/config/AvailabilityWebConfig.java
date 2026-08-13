package com.pronto.availability.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers a {@link RoleRequiredInterceptor} for every {@code /api/availability/**} route,
 * so the {@code role = PROFESSIONAL} restriction
 * ({@code docs/architecture/api-contract-bookings.md} §0.1) is enforced before Spring
 * resolves {@code @Valid} request bodies — see {@link RoleRequiredInterceptor}'s javadoc for
 * the full ordering-bug rationale this fixes.
 *
 * <p>A single blanket-pattern registration is correct here (unlike
 * {@code bookings.config.BookingsWebConfig}) because both §2.10 and §2.11 endpoints in this
 * package require the same single role — the same simple case
 * {@code issues.config.IssuesWebConfig}/{@code storage.config.StorageWebConfig} were in for
 * Milestone 2 (§0.1).
 */
@Configuration
public class AvailabilityWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.PROFESSIONAL.name()))
                .addPathPatterns("/api/availability/**");
    }
}
