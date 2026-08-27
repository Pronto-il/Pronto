package com.pronto.auth.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

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
        // A representative limited route. MockHttpServletRequest's URI is "" by default, which would
        // make the MS5 refusal-log assertions below pass vacuously.
        request.setRequestURI("/api/auth/login");
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

    // ---- Production MS5: the refusal log is the evidence TRUSTED_PROXIES is validated with -------

    @Test
    void aRefusalLogsTheResolvedClientKey_soProductionCanProveWhichBucketWasSpent() {
        // The runbook's proxy-validation procedure reads this line. Two public addresses driven to a
        // refusal must show two different `client` values; if TRUSTED_PROXIES were wrong they would
        // both show the load balancer's address and the shared-bucket failure would be visible here
        // rather than inferred from response codes.
        List<ILoggingEvent> events = captureRefusal(
                new AuthRateLimitInterceptor(1, Duration.ofMinutes(5), new ClientIpResolver("10.0.0.0/16")),
                "10.0.4.9", "203.0.113.7");

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .startsWith("pronto.ratelimit.refused")
                    .contains("client=203.0.113.7")
                    .contains("route=/api/auth/login");
        });
    }

    @Test
    void aRefusalLogsTheRealPeer_notAForgedForwardedHeader() {
        // A direct read of ClientIpResolver's decision, which nothing else in the application
        // exposes. With no trusted proxy configured the header is ignored entirely, so the attacker's
        // chosen value must not appear anywhere in the line.
        List<ILoggingEvent> events = captureRefusal(
                new AuthRateLimitInterceptor(1, Duration.ofMinutes(5), new ClientIpResolver("")),
                "203.0.113.7", "198.51.100.5");

        assertThat(events).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains("client=203.0.113.7")
                .doesNotContain("198.51.100.5"));
    }

    @Test
    void aRefusalLogNeverCarriesCredentials() {
        // The limiter sits on the login and registration routes, so the request it refuses is
        // routinely carrying a password or an Authorization header. Nothing from the request may
        // reach the log except the resolved key and the fixed route.
        AuthRateLimitInterceptor interceptor =
                new AuthRateLimitInterceptor(1, Duration.ofMinutes(5), new ClientIpResolver(""));

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            MockHttpServletRequest first = request("203.0.113.7", null);
            first.addHeader("Authorization", "Bearer test-only-not-a-real-token-aaaaaaaaaaaa");
            interceptor.preHandle(first, new MockHttpServletResponse(), new Object());

            MockHttpServletRequest second = request("203.0.113.7", null);
            second.addHeader("Authorization", "Bearer test-only-not-a-real-token-aaaaaaaaaaaa");
            second.setQueryString("code=483920");
            assertThatThrownBy(() -> interceptor.preHandle(second, new MockHttpServletResponse(), new Object()))
                    .isInstanceOf(ApiException.class);

            String logged = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logged)
                    .doesNotContain("Bearer")
                    .doesNotContain("test-only-not-a-real-token-aaaaaaaaaaaa")
                    .doesNotContain("483920");
        } finally {
            detachAppender(appender);
        }
    }

    /** Drives {@code interceptor} (limit 1) to its refusal and returns what it logged. */
    private static List<ILoggingEvent> captureRefusal(AuthRateLimitInterceptor interceptor,
                                                      String peer, String forwardedFor) {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            call(interceptor, peer, forwardedFor);
            assertThatThrownBy(() -> call(interceptor, peer, forwardedFor)).isInstanceOf(ApiException.class);
            return List.copyOf(appender.list);
        } finally {
            detachAppender(appender);
        }
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(AuthRateLimitInterceptor.class)).addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AuthRateLimitInterceptor.class)).detachAppender(appender);
        appender.stop();
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
