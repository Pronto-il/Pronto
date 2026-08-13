package com.pronto.storage.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers a {@link RoleRequiredInterceptor} for every {@code /api/storage/**} route, so the
 * {@code role = CUSTOMER} restriction ({@code docs/architecture/api-contract-issues.md}
 * §0.1) is enforced before Spring resolves {@code @RequestParam}/multipart parts — see
 * {@link RoleRequiredInterceptor}'s javadoc for the full ordering-bug rationale this fixes
 * (a professional token hitting {@code POST /api/storage/images} with no {@code file} part
 * previously got {@code 400 VALIDATION_ERROR} instead of {@code 403 FORBIDDEN}).
 *
 * <p>Deliberately lives here, not in {@code common} — see
 * {@code issues.config.IssuesWebConfig}'s javadoc for the identical rationale (this class
 * mirrors it for {@code storage}'s routes).
 */
@Configuration
public class StorageWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name()))
                .addPathPatterns("/api/storage/**");
    }
}
