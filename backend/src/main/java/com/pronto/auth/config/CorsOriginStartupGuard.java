package com.pronto.auth.config;

import com.pronto.common.config.ProntoEnvironment;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-fast startup guard for the browser CORS allow-list, {@code pronto.cors.allowed-origins}.
 *
 * <p>Production MS4. {@code application.yml} defaults this to {@code http://localhost:5173} — the
 * Vite dev server — because that is the right default for the only environment that had existed
 * when it was written. A Production deployment that forgets {@code CORS_ALLOWED_ORIGINS} therefore
 * ships an API whose sole permitted browser origin is a developer's laptop: every real request is
 * blocked by the browser, and the deployed allow-list names an origin that has nothing to do with
 * the platform. Neither symptom points at CORS from the outside — the frontend simply appears
 * broken — which is exactly the class of failure a startup check converts into one clear line.
 *
 * <h2>The four rules, and why each one</h2>
 *
 * <ol>
 *   <li><b>Not empty.</b> {@code CORS_ALLOWED_ORIGINS=""} produces an allow-list of nothing, which
 *       rejects every cross-origin call with no diagnostic anywhere.</li>
 *   <li><b>No wildcard.</b> {@code CorsConfiguration} accepts {@code *} silently. Pronto's blast
 *       radius today is bounded — {@code SecurityConfig} leaves {@code allowCredentials} false and
 *       the JWT travels in an {@code Authorization} header rather than a cookie, so {@code *} does
 *       not by itself hand an attacker's page an authenticated session. It is still a permission
 *       nobody decided to grant, and the moment a cookie or {@code allowCredentials} is introduced
 *       it becomes the whole vulnerability. Refused rather than left to be noticed later.</li>
 *   <li><b>No development hosts.</b> {@code localhost}, {@code 127.0.0.1}, {@code ::1} and
 *       {@code 0.0.0.0} in a Production allow-list mean one of two things: the variable was never
 *       set, or a developer origin was left in. Both are worth failing on.</li>
 *   <li><b>{@code https} only.</b> An {@code http} origin in Production is either a plaintext
 *       deployment or a copied dev value. Since every JWT this API issues travels from that origin,
 *       permitting it is permitting session tokens over the wire in clear.</li>
 * </ol>
 *
 * <p><b>Nothing here fires outside a production-like environment</b>
 * ({@link ProntoEnvironment#isProductionLike()}), so the default keeps working untouched for
 * {@code local}, {@code test} and {@code demo} — including the {@code http://localhost:5173} the
 * README's demo recipe relies on.
 *
 * <p>Kept separate from {@link ProductionHardeningStartupGuard} rather than folded into it: that
 * class is about secret material and proxy trust, this one is about a browser policy, and the two
 * have no shared inputs.
 */
@Component
public class CorsOriginStartupGuard {

    /** Hosts that can only mean "this is a developer machine". */
    private static final Set<String> DEVELOPMENT_HOSTS =
            Set.of("localhost", "127.0.0.1", "0.0.0.0", "[::1]", "::1");

    private final ProntoEnvironment environment;
    private final List<String> allowedOrigins;

    public CorsOriginStartupGuard(ProntoEnvironment environment,
                                   @Value("${pronto.cors.allowed-origins:}") List<String> allowedOrigins) {
        this.environment = environment;
        this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
    }

    @PostConstruct
    public void validate() {
        if (!environment.isProductionLike()) {
            return;
        }

        List<String> failures = new ArrayList<>();
        List<String> origins = allowedOrigins.stream()
                .map(origin -> origin == null ? "" : origin.trim())
                .filter(origin -> !origin.isEmpty())
                .toList();

        if (origins.isEmpty()) {
            failures.add("pronto.cors.allowed-origins (CORS_ALLOWED_ORIGINS) is empty. Every "
                    + "cross-origin browser request would be rejected, so the frontend could not call this "
                    + "API at all. Set it to the frontend's origin, e.g. https://app.example.com.");
        }

        for (String origin : origins) {
            if (origin.contains("*")) {
                failures.add("pronto.cors.allowed-origins (CORS_ALLOWED_ORIGINS) contains the wildcard "
                        + "origin '" + origin + "'. Name the frontend origins explicitly.");
                continue;
            }
            String host = hostOf(origin);
            if (DEVELOPMENT_HOSTS.contains(host)) {
                failures.add("pronto.cors.allowed-origins (CORS_ALLOWED_ORIGINS) contains the development "
                        + "origin '" + origin + "'. This is what an unset CORS_ALLOWED_ORIGINS looks like: "
                        + "application.yml defaults to the Vite dev server.");
                continue;
            }
            if (!origin.toLowerCase(Locale.ROOT).startsWith("https://")) {
                failures.add("pronto.cors.allowed-origins (CORS_ALLOWED_ORIGINS) contains the non-HTTPS "
                        + "origin '" + origin + "'. Every JWT this API issues travels from that origin, so "
                        + "permitting it in production permits session tokens in clear.");
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: pronto.environment='" + environment.name() + "' with an unsafe CORS "
                            + "configuration.\n  - " + String.join("\n  - ", failures));
        }
    }

    /**
     * The host portion of {@code scheme://host[:port]}, lower-cased. Deliberately string-sliced
     * rather than parsed through {@link java.net.URI}: an origin that is malformed enough to fail
     * URI parsing still has to be classified, and this is only ever used to recognize the four
     * development hosts above — a value it cannot make sense of falls through to the scheme check,
     * which any non-{@code https} string fails anyway.
     */
    private static String hostOf(String origin) {
        String remainder = origin;
        int schemeEnd = remainder.indexOf("://");
        if (schemeEnd >= 0) {
            remainder = remainder.substring(schemeEnd + 3);
        }
        int pathStart = remainder.indexOf('/');
        if (pathStart >= 0) {
            remainder = remainder.substring(0, pathStart);
        }
        // IPv6 literals are bracketed, and the colon inside them is not a port separator.
        if (remainder.startsWith("[")) {
            int close = remainder.indexOf(']');
            if (close >= 0) {
                return remainder.substring(0, close + 1).toLowerCase(Locale.ROOT);
            }
        }
        int portStart = remainder.indexOf(':');
        if (portStart >= 0) {
            remainder = remainder.substring(0, portStart);
        }
        return remainder.toLowerCase(Locale.ROOT);
    }
}
