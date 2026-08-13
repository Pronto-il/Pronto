package com.pronto.issues.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers a {@link RoleRequiredInterceptor} for every {@code /api/issues/**} route, so the
 * {@code role = CUSTOMER} restriction ({@code docs/architecture/api-contract-issues.md}
 * §0.1) is enforced before Spring resolves {@code @Valid} request bodies — see
 * {@link RoleRequiredInterceptor}'s javadoc for the full ordering-bug rationale this fixes.
 *
 * <p>Deliberately lives here, not in {@code common} — {@code common} must never depend on a
 * domain package or hard-code which routes require which role (see {@code common/README.md});
 * each domain package instead registers its own required role for its own routes, keeping
 * {@link RoleRequiredInterceptor} itself generic/reusable infrastructure.
 *
 * <p>Registering on {@code /api/issues/**} (not the two individual endpoint paths) means any
 * future endpoint added to {@code IssuesController} is automatically covered without an edit
 * here.
 */
@Configuration
public class IssuesWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name()))
                .addPathPatterns("/api/issues/**");
    }
}
