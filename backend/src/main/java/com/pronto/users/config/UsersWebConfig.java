package com.pronto.users.config;

import com.pronto.auth.security.AuthRateLimitInterceptor;
import com.pronto.auth.security.ClientIpResolver;
import com.pronto.common.security.RoleRequiredInterceptor;
import com.pronto.users.entity.UserRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Route-level gating for {@code /api/users/**}.
 *
 * <p>{@code PUT /api/users/me} is {@code CUSTOMER}-only, and — as of the Production MS1 pre-DONE
 * audit — rate limited.
 *
 * <p><b>Why a rate limit on a profile endpoint.</b> Since MS1, {@code PUT /api/users/me} accepts a
 * phone number, normalizes it, and answers {@code 409 DUPLICATE_PHONE} when it belongs to somebody
 * else. That makes it an oracle for "is this number registered with Pronto", and it was the only
 * such surface with no limiter at all: any authenticated account could walk the Israeli mobile range
 * as fast as it could send requests. {@code POST /api/auth/phone/capture} already had one; this
 * closes the gap by reusing the same interceptor rather than inventing a second mechanism.
 *
 * <p>The threshold is deliberately generous — a profile edit is a rare, deliberate act, so 20 per 15
 * minutes never inconveniences a real user editing their address or name, while reducing an
 * enumeration sweep from unbounded to 80 probes an hour per source. It is applied to the whole
 * endpoint rather than only to requests that change the phone, because the limiter runs before the
 * body is bound and because a rate that never bites legitimate profile edits costs nothing to apply
 * uniformly.
 */
@Configuration
public class UsersWebConfig implements WebMvcConfigurer {

    private final ClientIpResolver clientIpResolver;

    public UsersWebConfig(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name(), "PUT"))
                .addPathPatterns("/api/users/me");
        registry.addInterceptor(new AuthRateLimitInterceptor(20, Duration.ofMinutes(15), clientIpResolver))
                .addPathPatterns("/api/users/me");
    }
}
