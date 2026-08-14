package com.pronto.auth.security;

import com.pronto.common.dto.RateLimitDetails;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple per-client-IP, in-memory, fixed-window rate limiter for a single {@code
 * /api/auth/*} endpoint. See {@code docs/architecture/hardening-plan.md} §5.2.
 *
 * <p><b>What this closes.</b> Per-account login lockout ({@code AuthService.login}, 5 failed
 * attempts) doesn't catch an attacker distributing guesses across many different accounts,
 * and {@code POST /api/auth/verify}'s 6-digit code (1-in-1,000,000 per guess, 15-minute
 * validity) had no attempt cap at all. This interceptor bounds request *volume* per source
 * IP, independently of the per-account mechanisms above, which are unchanged.
 *
 * <p><b>Fixed window, not sliding/token-bucket.</b> Each client IP gets a counter that resets
 * {@code windowMillis} after its first request in the current window (not a rolling
 * average). Simpler to reason about and implement correctly than a sliding window or
 * token-bucket, and sufficient for the generous, non-adversarial-traffic-tuned thresholds
 * this is configured with (see {@code auth.config.AuthWebConfig}) — allows brief bursts at a
 * window boundary, which is an accepted trade-off, not a defect, at this scale.
 *
 * <p><b>{@code ConcurrentHashMap}-backed, no new Maven dependency.</b> Sufficient at this
 * project's traffic scale and consistent with its documented single-instance-deployment
 * status ({@code docs/architecture/hardening-plan.md} §4.3's reasoning) — a distributed
 * limiter (Redis-backed, etc.) or a library like bucket4j/resilience4j would be
 * over-engineering for what's needed here.
 *
 * <p><b>One instance per route.</b> {@code auth.config.AuthWebConfig} registers a separate
 * instance of this class per {@code /api/auth/*} route, each with its own threshold/window
 * and its own counter map — so counters are naturally isolated per endpoint without this
 * class needing to key on path as well as IP.
 *
 * <p><b>IP resolution.</b> {@link HttpServletRequest#getRemoteAddr()} only. This application
 * has never run behind a reverse proxy/load balancer in any tested environment
 * ({@code docs/architecture/hardening-plan.md} §2.1) — trusting an {@code X-Forwarded-For}
 * header without a real proxy in front to set/sanitize it would itself be a spoofing vector
 * (any client could set that header to claim to be a different IP, defeating the limiter or
 * framing another client). Revisit if/when a real reverse proxy is introduced in front of
 * this application.
 *
 * <p><b>No eviction.</b> Window entries for IPs that stop sending requests are never purged
 * from {@link #windowsByIp}, so memory usage grows with the number of distinct IPs seen over
 * the process's lifetime. Acceptable at this project's current scale (no deployed
 * environment exists yet, per {@code docs/architecture/hardening-plan.md}'s stated ground
 * truth) — revisit with a scheduled sweep or a bounded/expiring cache if this is ever a real
 * concern under sustained real traffic.
 */
public class AuthRateLimitInterceptor implements HandlerInterceptor {

    private static final String RETRY_AFTER_HEADER = "Retry-After";

    private record Window(long windowStartMillis, int count) {
    }

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windowsByIp = new ConcurrentHashMap<>();

    public AuthRateLimitInterceptor(int maxRequests, Duration window) {
        this.maxRequests = maxRequests;
        this.windowMillis = window.toMillis();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientIp = request.getRemoteAddr();
        long now = System.currentTimeMillis();

        Window current = windowsByIp.compute(clientIp, (ip, existing) ->
                (existing == null || now - existing.windowStartMillis() >= windowMillis)
                        ? new Window(now, 1)
                        : new Window(existing.windowStartMillis(), existing.count() + 1));

        if (current.count() > maxRequests) {
            long windowRemainingMillis = windowMillis - (now - current.windowStartMillis());
            long retryAfterSeconds = Math.max(1, Math.ceilDiv(windowRemainingMillis, 1000));
            response.setHeader(RETRY_AFTER_HEADER, String.valueOf(retryAfterSeconds));
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "Too many requests from this client. Please try again later.",
                    new RateLimitDetails(retryAfterSeconds));
        }
        return true;
    }
}
