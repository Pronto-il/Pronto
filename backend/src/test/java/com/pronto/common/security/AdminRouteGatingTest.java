package com.pronto.common.security;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.config.ProfessionalsWebConfig;
import com.pronto.users.entity.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Playbook's MS1 required test: <b>an unauthorized customer or professional cannot use the
 * admin approval endpoints.</b>
 *
 * <p>Two halves, because either one alone would be worthless. The first is that the gate itself
 * refuses everyone who is not an {@code ADMIN}. The second — the one that actually matters, and
 * the one a service-level test can never give you — is that the gate is <em>wired to the admin
 * routes</em>: {@code ProfessionalApprovalService} deliberately does not re-check the role, so if
 * the registration in {@code ProfessionalsWebConfig} were removed or misspelled, every approval
 * endpoint would be open to any authenticated caller and no other test in this repository would
 * notice.
 *
 * <p>Reading the registrations back needs reflection into {@link InterceptorRegistry}'s internals,
 * which is ugly and is the price of this backend having no Spring-context test harness yet (MS0
 * finding; D3 assigns building one to MS5). The alternative was to assert nothing about the wiring
 * at all.
 */
class AdminRouteGatingTest {

    private static final String ADMIN_LIST_PATH = "/api/admin/professionals";
    private static final String ADMIN_APPROVE_PATH = "/api/admin/professionals/50/approve";
    private static final String ADMIN_DOCUMENT_PATH = "/api/admin/professionals/50/verification-document";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(Long userId, String role) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static boolean preHandle(String path) {
        RoleRequiredInterceptor interceptor = new RoleRequiredInterceptor(UserRole.ADMIN.name());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void customerCannotReachAnAdminRoute() {
        authenticateAs(1L, UserRole.CUSTOMER.name());

        assertThatThrownBy(() -> preHandle(ADMIN_APPROVE_PATH))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void professionalCannotReachAnAdminRoute_notEvenTheirOwnReview() {
        // Specifically including the professional themselves: approval is not a self-service
        // operation, and the review screen exposes another party's judgment of them.
        authenticateAs(10L, UserRole.PROFESSIONAL.name());

        assertThatThrownBy(() -> preHandle(ADMIN_DOCUMENT_PATH))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void unauthenticatedCallerCannotReachAnAdminRoute() {
        assertThatThrownBy(() -> preHandle(ADMIN_LIST_PATH))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void adminIsAllowedThrough() {
        authenticateAs(7L, UserRole.ADMIN.name());

        assertThatCode(() -> assertThat(preHandle(ADMIN_APPROVE_PATH)).isTrue()).doesNotThrowAnyException();
    }

    @Test
    void adminGateIsActuallyRegisteredOnTheAdminPaths_collectionRouteIncluded() {
        List<String> adminPatterns = adminGatedPatterns();

        // Both the collection route (no trailing segment) and everything beneath it. If only the
        // "/**" pattern were registered, the queue listing's gating would depend on which path
        // matcher Spring happened to be configured with.
        assertThat(adminPatterns).contains("/api/admin/professionals", "/api/admin/professionals/**");
    }

    @Test
    void theProfessionalSelfServiceRoutesAreNotAdminGated_andViceVersa() {
        // The two audiences must not be merged: a blanket admin pattern over
        // /api/professionals/** would lock professionals out of their own profile.
        assertThat(adminGatedPatterns()).noneMatch(pattern -> pattern.startsWith("/api/professionals"));
    }

    /**
     * The path patterns {@code ProfessionalsWebConfig} registers an {@code ADMIN}
     * {@link RoleRequiredInterceptor} against.
     */
    private static List<String> adminGatedPatterns() {
        InterceptorRegistry registry = new InterceptorRegistry();
        new ProfessionalsWebConfig().addInterceptors(registry);

        List<String> patterns = new ArrayList<>();
        for (InterceptorRegistration registration : readRegistrations(registry)) {
            Object interceptor = read(registration, "interceptor");
            if (!(interceptor instanceof RoleRequiredInterceptor)) {
                continue;
            }
            if (!UserRole.ADMIN.name().equals(read(interceptor, "requiredRole"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> includes = (List<String>) read(registration, "includePatterns");
            patterns.addAll(includes);
        }
        return patterns;
    }

    @SuppressWarnings("unchecked")
    private static List<InterceptorRegistration> readRegistrations(InterceptorRegistry registry) {
        return (List<InterceptorRegistration>) read(registry, "registrations");
    }

    private static Object read(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Spring's " + target.getClass().getSimpleName() + " no longer exposes '" + fieldName
                            + "'. Re-derive this assertion rather than deleting it -- it is the only "
                            + "check that the admin routes are gated at all.", e);
        }
    }
}
