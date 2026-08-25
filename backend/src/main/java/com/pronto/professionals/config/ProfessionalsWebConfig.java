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
 *
 * <p><b>MS1</b> adds the {@code ADMIN}-only operator surface
 * ({@code professionals.controller.AdminProfessionalsController}). It lives under its own
 * {@code /api/admin/professionals} prefix rather than alongside the routes above, which is what
 * makes a blanket {@code /**} pattern correct there: the prefix has exactly one audience, so
 * there is no either-role route underneath it for a wildcard to accidentally expose. This is the
 * only gate on that surface — {@code ProfessionalApprovalService} deliberately does not re-check
 * the role, and this interceptor's {@code preHandle} runs before {@code @Valid} body resolution,
 * so a non-{@code ADMIN} caller gets {@code 403} rather than a {@code 400} describing the
 * endpoint's request shape.
 */
@Configuration
public class ProfessionalsWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.PROFESSIONAL.name()))
                .addPathPatterns("/api/professionals/me", "/api/professionals/me/profile-image",
                        "/api/professionals/me/sub-services",
                        // Production MS2: the professional's current device position
                        // (professionals.controller.ProfessionalLocationController). Added to
                        // this literal list for the same reason every other route here is --
                        // the list does not pick up new routes by itself, and an ungated
                        // location endpoint would let any authenticated account write a
                        // position row.
                        "/api/professionals/me/location");
        // Both patterns, deliberately: the collection route itself has no trailing segment, and
        // relying on a particular path-matcher's treatment of "/**" against the bare prefix is not
        // a thing to leave to interpretation when the answer decides whether an endpoint is gated.
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.ADMIN.name()))
                .addPathPatterns("/api/admin/professionals", "/api/admin/professionals/**");
    }
}
