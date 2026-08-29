package com.pronto.reviews.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers a {@link RoleRequiredInterceptor} for the {@code CUSTOMER}-only write routes
 * ({@code POST /api/reviews}, {@code PUT}/{@code DELETE /api/reviews/{reviewId}}).
 * {@code GET /api/reviews} (either role) is left ungated — same "route-level gate abstains,
 * service layer authorizes/none needed" precedent as {@code issues.config.IssuesWebConfig}'s
 * handling of its either-role {@code GET /api/issues/{id}} route.
 *
 * <p>{@code POST /api/reviews} and {@code GET /api/reviews} share an identical literal
 * path, differing only by HTTP method — Spring's {@code addPathPatterns} can't distinguish
 * them by itself, so this registration uses {@link RoleRequiredInterceptor}'s HTTP-method-
 * scoped constructor (added for this exact case) rather than the plain single-arg one every
 * other package's config uses.
 *
 * <p><b>That method scoping is what let {@code GET} become public on 2026-08-29 without touching
 * this class at all.</b> The {@code POST}-scoped registration below already declines to run on a
 * {@code GET}, and the {@code /api/reviews/*} registration covers a path a {@code GET} never uses
 * (there is no get-by-id endpoint). So the only change needed was one {@code GET}-scoped
 * {@code permitAll} line in {@code auth.config.SecurityConfig}; the write gates here are
 * byte-for-byte unchanged and remain the thing that stops a guest — or a professional, or an
 * admin — creating, editing or deleting a review. Do not widen either pattern: a blanket
 * {@code permitAll} on this controller would take the {@code POST} gate with it.
 */
@Configuration
public class ReviewsWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name(), "POST"))
                .addPathPatterns("/api/reviews");
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name()))
                .addPathPatterns("/api/reviews/*");
    }
}
