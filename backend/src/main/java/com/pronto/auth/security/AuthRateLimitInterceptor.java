package com.pronto.auth.security;

import com.pronto.common.dto.RateLimitDetails;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-client, in-memory, fixed-window rate limiter for a single {@code /api/auth/*} route.
 *
 * <p><b>What this closes.</b> Per-account lockout does not catch an attacker distributing guesses
 * across many accounts, and the OTP endpoints' per-challenge attempt cap does not bound how many
 * <em>challenges</em> a caller may start. This bounds request volume per source, independently of
 * both.
 *
 * <p><b>Client identity comes from {@link ClientIpResolver}, not {@code getRemoteAddr()}.</b> That
 * is the Production MS1 change and the reason this class is no longer safe to reason about as
 * "keyed on the peer address": behind an AWS ALB the peer address is the load balancer, so keying
 * on it would collapse every user onto one counter. See {@code ClientIpResolver} for why the
 * forwarded header is trusted only from configured networks.
 *
 * <p><b>Fixed window, not sliding/token-bucket.</b> Each client gets a counter that resets
 * {@code windowMillis} after its first request in the current window. Simpler to reason about and
 * sufficient for the deliberately generous thresholds in {@code auth.config.AuthWebConfig}; it
 * allows a brief burst at a window boundary, which is an accepted trade-off rather than a defect.
 *
 * <p><b>Single-instance only, and bounded.</b> The counters live in this JVM's heap, so two
 * application instances would enforce two independent limits and a restart resets everything. That
 * is a real limitation, recorded rather than papered over — distributed rate limiting is an
 * MS4/MS5 concern, and this class must not be described as multi-instance safe. What was fixed here
 * is the unbounded growth: entries used to accumulate forever, one per distinct address ever seen,
 * which is a slow memory leak that an attacker can drive deliberately by spoofing source addresses.
 * {@link #sweepIfDue} now evicts expired windows, and {@link #MAX_TRACKED_CLIENTS} caps the map
 * even under an active flood.
 */
public class AuthRateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitInterceptor.class);

    private static final String RETRY_AFTER_HEADER = "Retry-After";

    /**
     * Hard ceiling on distinct clients tracked at once. Reaching it means an eviction sweep just
     * failed to free anything, i.e. a flood of distinct sources inside a single window. The response
     * is to clear the map rather than to keep growing: forgetting counters briefly under attack
     * costs one window of limiting, while an unbounded map costs the whole process.
     */
    static final int MAX_TRACKED_CLIENTS = 100_000;

    private record Window(long windowStartMillis, int count) {
    }

    private final int maxRequests;
    private final long windowMillis;
    private final ClientIpResolver clientIpResolver;
    private final boolean onlyWhenUnauthenticated;
    private final ConcurrentHashMap<String, Window> windowsByClient = new ConcurrentHashMap<>();
    private final AtomicLong nextSweepMillis = new AtomicLong(0);

    public AuthRateLimitInterceptor(int maxRequests, Duration window, ClientIpResolver clientIpResolver) {
        this(maxRequests, window, clientIpResolver, false);
    }

    /**
     * <b>Opt-in: limit only callers who have no account behind them.</b> With
     * {@code onlyWhenUnauthenticated = true}, a request carrying a verified JWT principal skips
     * this limiter entirely and an anonymous one is counted per source address as usual.
     *
     * <p>Added for {@code POST /api/storage/images} when guests gained the ability to attach
     * photos. An authenticated upload was already bounded by something stronger than an IP counter
     * — it requires a registered account with a verified phone number — and imposing a new limit on
     * it would be a behaviour change for existing customers that this feature has no business
     * making. An anonymous upload has no such bound, and writing 8 MB objects into the uploads
     * bucket is the one thing a guest can now do that costs real money, so per-source limiting is
     * what replaces the account requirement.
     *
     * <p>Every pre-existing registration ({@code auth}, {@code issues}, {@code users}) uses the
     * three-argument constructor and is unaffected.
     */
    public AuthRateLimitInterceptor(int maxRequests, Duration window, ClientIpResolver clientIpResolver,
                                     boolean onlyWhenUnauthenticated) {
        this.maxRequests = maxRequests;
        this.windowMillis = window.toMillis();
        this.clientIpResolver = clientIpResolver;
        this.onlyWhenUnauthenticated = onlyWhenUnauthenticated;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (onlyWhenUnauthenticated && isAuthenticated()) {
            return true;
        }
        String client = clientIpResolver.resolve(request);
        long now = System.currentTimeMillis();

        sweepIfDue(now);

        Window current = windowsByClient.compute(client, (key, existing) ->
                (existing == null || now - existing.windowStartMillis() >= windowMillis)
                        ? new Window(now, 1)
                        : new Window(existing.windowStartMillis(), existing.count() + 1));

        if (current.count() > maxRequests) {
            long windowRemainingMillis = windowMillis - (now - current.windowStartMillis());
            long retryAfterSeconds = Math.max(1, Math.ceilDiv(windowRemainingMillis, 1000));
            response.setHeader(RETRY_AFTER_HEADER, String.valueOf(retryAfterSeconds));
            logRefusal(client, request, current.count(), retryAfterSeconds);
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "Too many requests from this client. Please try again later.",
                    new RateLimitDetails(retryAfterSeconds));
        }
        return true;
    }

    /**
     * Reads the same principal {@code common.security.RoleRequiredInterceptor} reads, populated
     * earlier in the filter chain by {@link JwtAuthenticationFilter} — not a parallel notion of
     * "authenticated".
     */
    private static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser;
    }

    /**
     * The one line that makes {@code TRUSTED_PROXIES} verifiable in Production.
     *
     * <p><b>Why this exists.</b> {@code TRUSTED_PROXIES} is the highest-consequence value in the
     * deployment: set too narrow, every user in the world shares one counter and registration caps
     * platform-wide; set to something publicly reachable, any caller evades limiting with one
     * header. Before this line the only observable symptom of either failure was the 429 itself,
     * which is identical in both the healthy and the broken case — so the deployment could only be
     * validated by inference. This states the answer directly: the key the limiter actually used.
     *
     * <p>It is what turns the runbook's proxy-validation steps into evidence rather than argument.
     * Driving two different public addresses to a refusal must produce two different {@code client}
     * values, and sending a forged {@code X-Forwarded-For} must produce the sender's real address
     * here rather than the forged one — that second case is a direct read of
     * {@link ClientIpResolver}'s decision, which nothing else exposes.
     *
     * <p><b>What is deliberately not logged.</b> The resolved key, the request URI, the count and
     * the retry-after — and nothing else from the request. No {@code Authorization} header, no
     * query string, no body, no email, no phone number, no OTP. The URI is safe because every
     * limited route is a fixed path ({@code /api/auth/*}, {@code /api/users/*}) with no identifier
     * embedded in it; if a limited route ever gains a path variable carrying user data, this must
     * log the matched pattern rather than the URI.
     *
     * <p>The event key is {@code pronto.ratelimit.refused}, deliberately in the same stable,
     * greppable form as {@code openai.request.failed} and {@code maps.geocode.rejected}, because a
     * CloudWatch metric filter keys on it (infra/terraform/observability.tf) and a renamed event
     * silently zeroes an alarm. IP addresses are personal data in some readings, which is one of the
     * reasons the log group has a bounded 30-day retention rather than an unbounded one.
     */
    private void logRefusal(String client, HttpServletRequest request, int count, long retryAfterSeconds) {
        log.warn("pronto.ratelimit.refused client={} route={} count={} limit={} retryAfterSeconds={}",
                client, request.getRequestURI(), count, maxRequests, retryAfterSeconds);
    }

    /**
     * Drops windows that have already elapsed, at most once per window duration.
     *
     * <p>The {@code compareAndSet} makes exactly one concurrent request perform the sweep; the rest
     * proceed immediately rather than queueing behind it. An expired entry is dead weight by
     * definition — the next request from that client starts a fresh window either way — so removing
     * it changes no limiting decision.
     */
    private void sweepIfDue(long now) {
        long due = nextSweepMillis.get();
        if (now < due || !nextSweepMillis.compareAndSet(due, now + windowMillis)) {
            return;
        }

        Iterator<Map.Entry<String, Window>> it = windowsByClient.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().windowStartMillis() >= windowMillis) {
                it.remove();
            }
        }

        if (windowsByClient.size() > MAX_TRACKED_CLIENTS) {
            log.warn("Auth rate-limit table exceeded {} live clients in one window; clearing it. "
                    + "This is what a distributed flood looks like.", MAX_TRACKED_CLIENTS);
            windowsByClient.clear();
        }
    }
}
