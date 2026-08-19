package com.pronto.users.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers a {@link RoleRequiredInterceptor} for {@code PUT /api/users/me} — {@code
 * CUSTOMER}-only (MS10 profile redesign §4.1). {@code GET}/{@code DELETE /api/users/me}
 * share the identical literal path but stay either-role/ungated, so this registration uses
 * {@link RoleRequiredInterceptor}'s HTTP-method-scoped constructor — mirrors
 * {@code reviews.config.ReviewsWebConfig}'s existing "same literal path, different HTTP
 * methods need different role gates" precedent exactly.
 */
@Configuration
public class UsersWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name(), "PUT"))
                .addPathPatterns("/api/users/me");
    }
}
