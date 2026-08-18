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
 * <p>{@code GET /api/storage/images/**} (retrieval) is deliberately left entirely
 * ungated here — no {@link RoleRequiredInterceptor} was ever needed for it, and, as of
 * backend MS9 ({@code docs/architecture/backend-ms9-presigned-image-urls-design.md}),
 * it isn't even gated by {@code auth.config.SecurityConfig}'s blanket
 * {@code .anyRequest().authenticated()} anymore — that route is now
 * {@code permitAll()} (scoped to {@code HttpMethod.GET} only; {@code POST} upload is
 * untouched and stays fully JWT-gated, exactly as this class's registration below still
 * enforces). This is a deliberate, necessary reversal of the previous design (a plain
 * HTML {@code <img src="...">} cannot attach an {@code Authorization} header, so a
 * JWT-gated retrieval route made every {@code <img>}-tag consumer of an image URL fail
 * with {@code net::ERR_BLOCKED_BY_ORB}), not a relaxation for its own sake — see that
 * design doc §4 for the full record, including why {@code permitAll()} at the Spring
 * Security layer does not mean "anyone can read any object by key": authorization moved
 * to URL-issuance time (a caller must already pass
 * {@code storage.service.StorageService#getPresignedUrl}'s ownership check, reusing
 * {@code storage.ImageKeyUtils} exactly as before, to ever obtain a working URL in the
 * first place) plus, in local mode only, an HMAC signature+expiry check on the {@code GET}
 * itself ({@code storage.service.StorageService#retrieveBySignedUrl}) — S3-mode presigned
 * URLs never reach this backend's {@code GET} route at all, since they point directly at
 * S3. Same "route-level gate abstains, authorization happens elsewhere" spirit as
 * {@code issues.config.IssuesWebConfig}'s handling of its either-role
 * {@code GET /api/issues/{id}} route, just with authorization moved one step further
 * upstream than a same-request service-layer check. This narrowing does not change
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
