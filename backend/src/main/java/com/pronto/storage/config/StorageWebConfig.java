package com.pronto.storage.config;

import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers a {@link RoleRequiredInterceptor} for exactly {@code POST /api/storage/images}
 * (upload), so the {@code role = CUSTOMER} restriction
 * ({@code docs/architecture/api-contract-issues.md} §0.1) is enforced before Spring resolves
 * {@code @RequestParam}/multipart parts — see {@link RoleRequiredInterceptor}'s javadoc for
 * the full ordering-bug rationale this fixes (a professional token hitting
 * {@code POST /api/storage/images} with no {@code file} part previously got
 * {@code 400 VALIDATION_ERROR} instead of {@code 403 FORBIDDEN}).
 *
 * <p>{@code GET /api/storage/images/**} (retrieval) is deliberately left ungated here —
 * narrowed from the original blanket {@code /api/storage/**} pattern (which made
 * {@code GET} CUSTOMER-only too, wrongly 403ing a professional — or a customer other than
 * the profile's owner — fetching a {@code professionals/}-prefixed profile-image URL, since
 * those are meant to be publicly viewable in listings). {@code auth.config.SecurityConfig}'s
 * blanket {@code .anyRequest().authenticated()} still guarantees a valid JWT of either role;
 * per-key authorization (ownership for {@code customers/}-prefixed issue images, none needed
 * for {@code professionals/}-prefixed profile images) is enforced in
 * {@code storage.service.StorageService#retrieve} via {@code storage.ImageKeyUtils} — same
 * "route-level gate abstains, service layer authorizes" precedent as
 * {@code issues.config.IssuesWebConfig}'s handling of its either-role
 * {@code GET /api/issues/{id}} route. This narrowing does not change
 * {@code POST /api/storage/images}'s behavior at all — the pattern below matches only the
 * exact literal path, not {@code POST}'s wildcard sibling.
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
                .addPathPatterns("/api/storage/images");
    }
}
