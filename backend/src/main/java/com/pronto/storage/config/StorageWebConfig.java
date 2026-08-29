package com.pronto.storage.config;

import com.pronto.auth.security.AuthRateLimitInterceptor;
import com.pronto.auth.security.ClientIpResolver;
import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

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

    /**
     * Anonymous uploads per source address, per 10 minutes. A real guest reaching a booking
     * attaches at most {@code PhotoUploader}'s {@code maxCount} of 6, so 20 covers three complete
     * reports plus retries from one household or office NAT, while bounding what a single source
     * can write into the uploads bucket to something that shows up as a rate-limit graph rather
     * than as a bill. Same figure and window as {@code issues.config.IssuesWebConfig}'s limit on
     * {@code /api/issues/classify}, deliberately: they are the two public routes with a real
     * per-request cost, and one number is easier to reason about than two.
     */
    private static final int GUEST_UPLOADS_PER_WINDOW = 20;
    private static final int GUEST_SESSIONS_PER_WINDOW = 20;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(10);

    private final ClientIpResolver clientIpResolver;

    public StorageWebConfig(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // The CUSTOMER restriction on uploads is UNCHANGED for anyone presenting a JWT: a
        // PROFESSIONAL token still gets 403 here, before multipart resolution, exactly as before.
        // What `allowAnonymous` adds is an abstention for a caller with no principal at all, which
        // is now a legitimate state (a guest) rather than automatically an unauthorized one. That
        // caller is authorized instead by auth.security.UploadOwnerResolver#requireIdentified in
        // StorageController, which answers 401 unless a valid guest-session token was presented.
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name(), true))
                .addPathPatterns("/api/storage/images");

        // Guests can now write objects into the uploads bucket without an account, which is the one
        // genuinely new cost surface this feature opens. The account requirement used to be the
        // bound; per-source limiting replaces it for callers who have no account, and deliberately
        // does NOT apply to authenticated customers -- their upload behaviour must be exactly what
        // it was.
        registry.addInterceptor(new AuthRateLimitInterceptor(
                        GUEST_UPLOADS_PER_WINDOW, RATE_LIMIT_WINDOW, clientIpResolver, true))
                .addPathPatterns("/api/storage/images");

        // Minting a session is free to serve, but it is the key to the namespace above, so an
        // unbounded mint endpoint would let one source sidestep the per-source upload limit only if
        // that limit were keyed on the session rather than the address. It is not -- both are keyed
        // on the address -- so this limit is defence in depth rather than the primary control.
        registry.addInterceptor(new AuthRateLimitInterceptor(
                        GUEST_SESSIONS_PER_WINDOW, RATE_LIMIT_WINDOW, clientIpResolver))
                .addPathPatterns("/api/storage/guest-sessions");
    }
}
