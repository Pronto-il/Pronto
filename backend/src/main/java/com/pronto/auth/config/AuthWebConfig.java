package com.pronto.auth.config;

import com.pronto.auth.security.AuthRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Registers a separate {@link AuthRateLimitInterceptor} instance per {@code /api/auth/*}
 * route, each with its own threshold/window, per
 * {@code docs/architecture/hardening-plan.md} §5.2. Mirrors the per-route interceptor
 * registration convention used by every other domain package's {@code *WebConfig} (e.g.
 * {@code issues.config.IssuesWebConfig}, {@code bookings.config.BookingsWebConfig}), except
 * this registers a rate limiter rather than a {@code common.security.RoleRequiredInterceptor}
 * — these three routes are pre-auth by definition ({@code auth.config.SecurityConfig}
 * {@code permitAll()}s {@code /api/auth/**}), so there is no role to gate.
 *
 * <p>Thresholds (generous enough not to disrupt realistic legitimate traffic, including
 * shared-NAT/office scenarios, while meaningfully bounding distributed credential-stuffing
 * and verification-code brute-forcing — see {@code hardening-plan.md} §5.2 for the full
 * reasoning behind each number):
 * <ul>
 *   <li>{@code POST /api/auth/register} — 10 requests / IP / 10 minutes.</li>
 *   <li>{@code POST /api/auth/login} — 30 requests / IP / 5 minutes.</li>
 *   <li>{@code POST /api/auth/verify} — 10 requests / IP / 15 minutes (matches the
 *       verification code's own validity window).</li>
 * </ul>
 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthRateLimitInterceptor(10, Duration.ofMinutes(10)))
                .addPathPatterns("/api/auth/register");
        registry.addInterceptor(new AuthRateLimitInterceptor(30, Duration.ofMinutes(5)))
                .addPathPatterns("/api/auth/login");
        registry.addInterceptor(new AuthRateLimitInterceptor(10, Duration.ofMinutes(15)))
                .addPathPatterns("/api/auth/verify");
    }
}
