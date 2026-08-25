package com.pronto.auth.config;

import com.pronto.auth.security.AuthRateLimitInterceptor;
import com.pronto.auth.security.ClientIpResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Registers a separate {@link AuthRateLimitInterceptor} instance per {@code /api/auth/*} route,
 * each with its own threshold, window and counter map — so counters are isolated per endpoint
 * without the interceptor needing to key on path as well as client.
 *
 * <p><b>Production MS1 covers every auth route, not three of them.</b> The previous configuration
 * limited {@code register}, {@code login} and {@code verify}; MS1 adds four more endpoints, two of
 * which (OTP resend and password-reset request) send a real message to a real person on every call
 * and are therefore the most abusable surface this API has ever had. An unlimited resend endpoint is
 * an SMS bill and a way to harass whoever owns a phone number.
 *
 * <p>Thresholds are per client per window. They are deliberately generous enough not to disrupt
 * legitimate traffic — including several people behind one office NAT — while bounding automated
 * abuse:
 * <ul>
 *   <li>{@code POST /register} — 10 / 10 min. Registration is rare and expensive (it sends mail).</li>
 *   <li>{@code POST /login} — 30 / 5 min. Generous: a person mistyping a password a few times, on a
 *       shared address, must not be locked out of the product.</li>
 *   <li>{@code POST /login/otp}, {@code /verify-email}, {@code /verify-phone} — 20 / 15 min. The
 *       real defence against guessing a code is the per-challenge 5-attempt cap; this only bounds
 *       how many challenges one source can grind through.</li>
 *   <li>{@code POST /otp/resend} — 10 / 15 min. Sits on top of the per-user 60s cooldown and 5/hour
 *       ceiling, and catches the case those cannot see: one source resending across many accounts.</li>
 *   <li>{@code POST /password-reset/request} — 5 / 15 min. The single most attractive endpoint for
 *       mass abuse, since it mails a stranger on demand.</li>
 *   <li>{@code POST /password-reset/confirm} — 20 / 15 min. Same reasoning as the OTP routes.</li>
 *   <li>{@code POST /phone/capture} — 10 / 15 min. Authenticated, and each call sends an SMS.</li>
 * </ul>
 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final ClientIpResolver clientIpResolver;

    public AuthWebConfig(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        limit(registry, 10, Duration.ofMinutes(10), "/api/auth/register");
        limit(registry, 30, Duration.ofMinutes(5), "/api/auth/login");
        limit(registry, 20, Duration.ofMinutes(15), "/api/auth/login/otp");
        limit(registry, 20, Duration.ofMinutes(15), "/api/auth/verify-email");
        limit(registry, 20, Duration.ofMinutes(15), "/api/auth/verify-phone");
        limit(registry, 10, Duration.ofMinutes(15), "/api/auth/otp/resend");
        limit(registry, 10, Duration.ofMinutes(15), "/api/auth/phone/capture");
        limit(registry, 5, Duration.ofMinutes(15), "/api/auth/password-reset/request");
        limit(registry, 20, Duration.ofMinutes(15), "/api/auth/password-reset/confirm");
    }

    private void limit(InterceptorRegistry registry, int maxRequests, Duration window, String path) {
        registry.addInterceptor(new AuthRateLimitInterceptor(maxRequests, window, clientIpResolver))
                .addPathPatterns(path);
    }
}
