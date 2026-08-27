package com.pronto.auth.config;

import com.pronto.auth.security.JsonAuthenticationEntryPoint;
import com.pronto.auth.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless JWT-based security configuration, per
 * {@code docs/architecture/api-contract.md} §3.1-3.2.
 *
 * <p>Public (no token required): {@code /actuator/health/**} (Milestone 0's health-check
 * acceptance criterion — must not regress now that spring-boot-starter-security is on the
 * classpath; widened from the exact path to the sub-tree by Production MS5 so that the ALB and
 * the ECS agent can reach {@code /actuator/health/readiness} and {@code /actuator/health/liveness}),
 * {@code /api/auth/**} (register/verify/login, which by definition happen
 * before the caller has a token), {@code GET /api/storage/images/**} (image retrieval,
 * backend MS9 — a plain {@code <img src>} cannot attach an {@code Authorization} header, so
 * this route authorizes via a presigned/HMAC-signed URL instead of a JWT; see
 * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §4 and
 * {@code storage.service.StorageService#retrieveBySignedUrl}. Scoped to {@code GET} only —
 * {@code POST /api/storage/images} (upload) is untouched by this exemption and stays fully
 * JWT-gated via the catch-all below), and {@code GET /api/categories} (MS11 — Services &amp;
 * Sub-services — non-sensitive reference data, deliberately public per
 * {@code docs/architecture/product-ms11-sub-services-design.md} §3.1, so it could someday
 * also serve the pre-login registration screen without redesign), and {@code GET
 * /api/service-areas} (MS4 — the closed Israeli region/city catalogue, public on exactly the
 * same grounds, and here the "pre-login registration screen" is not hypothetical: the
 * professional registration wizard cannot render its region and city selectors without it).
 * Everything else — including {@code /api/users/me} and any endpoint added by a later
 * milestone — requires a valid, non-revoked JWT.
 *
 * <p><b>{@code /ws/**} (the STOMP handshake, SOS realtime phase) is permitted here, and that is
 * not a weakening.</b> A browser's {@code WebSocket} constructor cannot attach an
 * {@code Authorization} header to the HTTP upgrade request, so gating the handshake on a JWT
 * would make the endpoint unusable rather than secure — the standard STOMP answer, and the one
 * taken here, is to authenticate one layer up, on the {@code CONNECT} frame, where the client
 * <em>can</em> send headers. {@code realtime.security.StompAuthChannelInterceptor} is that gate:
 * it resolves the same JWT through the same {@code JwtPrincipalResolver} this filter chain uses,
 * refuses the session outright when the token is missing/invalid/revoked, and allow-lists the
 * single destination a client may subscribe to. Opening the handshake therefore buys an
 * unauthenticated caller an open socket that can do nothing except receive an {@code ERROR}
 * frame. Deliberately scoped to {@code /ws/**} and nothing else.
 *
 * <p>CSRF and form-login are disabled: this is a stateless token API with no server-side
 * session/cookie-based auth, so neither applies.
 *
 * <p>CORS is enabled for {@code /api/**} so a browser frontend on a different origin (the
 * Vite dev server by default) can call this API — see {@link #corsConfigurationSource()}.
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;
    private final List<String> corsAllowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                           JsonAuthenticationEntryPoint authenticationEntryPoint,
                           @Value("${pronto.cors.allowed-origins}") List<String> corsAllowedOrigins) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Production MS1: the one authenticated route under /api/auth/**. Matched
                        // BEFORE the permitAll below, because Spring Security evaluates these in
                        // order and the first match wins — reversing the two lines would silently
                        // open it. It has to be authenticated: it attaches a phone number to the
                        // calling account, and an unauthenticated caller naming an account id would
                        // be the entire vulnerability.
                        .requestMatchers(HttpMethod.POST, "/api/auth/phone/capture").authenticated()
                        // Production MS5 widened this from the exact path "/actuator/health" to
                        // include its sub-paths. Spring Security's path matcher treats
                        // "/actuator/health" as EXACTLY that string, so the liveness and readiness
                        // groups added in application.yml -- /actuator/health/liveness and
                        // /actuator/health/readiness -- fell through to the authenticated catch-all
                        // and answered 401. An ALB target health check receiving 401 marks every
                        // task unhealthy and drains the service to zero, so this omission would have
                        // presented as a total outage on the first deploy.
                        //
                        // Widening leaks nothing further: `include: health` is the only exposed
                        // endpoint, so /actuator/** resolves to nothing but health and its groups,
                        // and `show-details: when-authorized` still withholds the per-indicator
                        // detail from an unauthenticated caller. What a stranger can learn is what a
                        // stranger could already learn by sending a request: whether the service is
                        // up.
                        .requestMatchers("/actuator/health/**", "/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/storage/images/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
                        // MS4 Part A: the closed region/city catalogue. Public for the same
                        // reason /api/categories is — professional registration needs it before
                        // an account exists — and no more sensitive: a list of Israeli city names.
                        .requestMatchers(HttpMethod.GET, "/api/service-areas").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Browser CORS policy for {@code /api/**}, sourced from
     * {@code pronto.cors.allowed-origins} (env var {@code CORS_ALLOWED_ORIGINS},
     * comma-separated, defaults to the Vite dev server {@code http://localhost:5173}).
     * Without this bean Spring Security rejects every cross-origin preflight
     * {@code OPTIONS} request with a 403 before it reaches any controller.
     * {@code /actuator/health} is not covered: it's polled server-to-server, not from a
     * browser, so it doesn't need a CORS policy.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsAllowedOrigins);
        // PATCH was missing here until PATCH /api/issues/{id}/category was added and every
        // cross-origin call to it failed preflight. It is not specific to that route: PATCH
        // /api/availability/blocks/{blockId} has existed since the weekly-calendar work and was
        // unreachable from any browser on a different origin for the same reason. The list is the
        // set of verbs this API actually answers, so PATCH belongs in it.
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Authorization"));
        // Every authenticated call carries `Authorization`, which makes it a non-simple request,
        // which means the browser preflights it. Without `Access-Control-Max-Age` on the response
        // Chromium caches that preflight decision for only 5 seconds, so a screen polling on any
        // interval longer than that re-asked permission before almost every single GET — measured
        // at 16-24 OPTIONS per minute on an idle customer home screen, roughly one per poll.
        //
        // This is a cache duration for a decision that does not vary, not a relaxation of it: the
        // allowed origins, methods and headers above are unchanged, every actual request is still
        // checked against them, and an expired or revoked token still fails on the request itself
        // (the preflight never carried credentials to begin with). 1800s is Spring's own default
        // for `CorsConfiguration.applyPermitDefaultValues()`, and is under Chromium's 2h cap.
        configuration.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /** BCrypt, Spring Security's default cost factor (10). Never plaintext. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
