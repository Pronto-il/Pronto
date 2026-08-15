package com.pronto.favorites.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers a {@link RoleRequiredInterceptor} for every {@code /api/favorites**} route — all
 * three ({@code POST}/{@code GET /api/favorites}, {@code DELETE
 * /api/favorites/{professionalId}}) require {@code CUSTOMER}, no either-role route exists in
 * this package (unlike {@code reviews}/{@code professionals}, which mix roles) — so, unlike
 * those two packages, a single blanket pattern is safe here, mirroring
 * {@code storage.config.StorageWebConfig}'s blanket-pattern precedent.
 */
@Configuration
public class FavoritesWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name()))
                .addPathPatterns("/api/favorites", "/api/favorites/**");
    }
}
