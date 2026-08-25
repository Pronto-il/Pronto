package com.pronto.auth.security;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-client fixed-window limiter, including the two Production MS1 changes: client identity
 * comes from {@link ClientIpResolver} rather than the raw peer address, and the counter table is
 * bounded rather than growing for the life of the process.
 */
class AuthRateLimitInterceptorTest {

    private static MockHttpServletRequest request(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private static void call(AuthRateLimitInterceptor interceptor, String peer, String forwardedFor) {
        interceptor.preHandle(request(peer, forwardedFor), new MockHttpServletResponse(), new Object());
    }

    @Test
    void requestsUpToTheThresholdPass_andTheNextIsRefused() {
        AuthRateLimitInterceptor interceptor =
                new AuthRateLimitInterceptor(3, Duration.ofMinutes(5), new ClientIpResolver(""));

        for (int i = 0; i < 3; i++) {
            call(interceptor, "203.0.113.7", null);
        }

        assertThatThrownBy(() -> call(interceptor, "203.0.113.7", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void aRefusalCarriesRetryAfter_onTheHeaderAndInTheBody() {
        AuthRateLimitInterceptor interceptor =
                new AuthRateLimitInterceptor(1, Duration.ofMinutes(5), new ClientIpResolver(""));
        call(interceptor, "203.0.113.7", null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> interceptor.preHandle(request("203.0.113.7", null), response, new Object()))
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).isNotNull());
        assertThat(response.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void countersAreIndependentPerClient() {
        AuthRateLimitInterceptor interceptor =
                new AuthRateLimitInterceptor(2, Duration.ofMinutes(5), new ClientIpResolver(""));
        call(interceptor, "203.0.113.7", null);
        call(interceptor, "203.0.113.7", null);

        assertThatCode(() -> call(interceptor, "198.51.100.5", null)).doesNotThrowAnyException();
    }

    @Test
    void behindATrustedProxy_countersAreKeyedOnTheRealClient_notTheLoadBalancer() {
        // The bug this closes: keyed on the peer address, every user behind an ALB shares one
        // counter, so a 10-per-window limit becomes a platform-wide cap of 10 and the limiter locks
        // the product instead of protecting it.
        AuthRateLimitInterceptor interceptor =
                new AuthRateLimitInterceptor(2, Duration.ofMinutes(5), new ClientIpResolver("10.0.0.0/16"));

        call(interceptor, "10.0.4.9", "203.0.113.7");
        call(interceptor, "10.0.4.9", "203.0.113.7");

        assertThatCode(() -> call(interceptor, "10.0.4.9", "198.51.100.5"))
                .as("a different real client, arriving through the same load balancer")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> call(interceptor, "10.0.4.9", "203.0.113.7"))
                .as("the first client is still limited")
                .isInstanceOf(ApiException.class);
    }

    @Test
    void withoutATrustedProxy_aSpoofedForwardedHeaderCannotBuyFreshBuckets() {
        AuthRateLimitInterceptor interceptor =
                new AuthRateLimitInterceptor(2, Duration.ofMinutes(5), new ClientIpResolver(""));

        call(interceptor, "203.0.113.7", "1.1.1.1");
        call(interceptor, "203.0.113.7", "2.2.2.2");

        assertThatThrownBy(() -> call(interceptor, "203.0.113.7", "3.3.3.3"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void theWindowResets() {
        AuthRateLimitInterceptor interceptor =
                new AuthRateLimitInterceptor(1, Duration.ofMillis(40), new ClientIpResolver(""));
        call(interceptor, "203.0.113.7", null);
        assertThatThrownBy(() -> call(interceptor, "203.0.113.7", null)).isInstanceOf(ApiException.class);

        await(60);

        assertThatCode(() -> call(interceptor, "203.0.113.7", null)).doesNotThrowAnyException();
    }

    @Test
    void expiredEntriesAreEvicted_soTheTableDoesNotGrowForTheLifeOfTheProcess() {
        // The pre-MS1 map had no eviction at all: one entry per distinct address ever seen, forever,
        // which an attacker can drive deliberately by rotating source addresses.
        AuthRateLimitInterceptor interceptor =
                new AuthRateLimitInterceptor(5, Duration.ofMillis(40), new ClientIpResolver(""));
        for (int i = 0; i < 200; i++) {
            call(interceptor, "203.0.113." + (i % 256), null);
        }

        await(60);
        call(interceptor, "198.51.100.5", null);   // triggers the sweep

        assertThat(trackedClients(interceptor))
                .as("the 200 elapsed windows are gone; only the live one remains")
                .isLessThanOrEqualTo(2);
    }

    @Test
    void theCeilingIsWellAboveAnyRealisticLegitimateLoad() {
        assertThat(AuthRateLimitInterceptor.MAX_TRACKED_CLIENTS).isGreaterThanOrEqualTo(10_000);
    }

    private static int trackedClients(AuthRateLimitInterceptor interceptor) {
        try {
            var field = AuthRateLimitInterceptor.class.getDeclaredField("windowsByClient");
            field.setAccessible(true);
            return ((java.util.Map<?, ?>) field.get(interceptor)).size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
