package com.pronto.auth.config;

import com.pronto.auth.security.GuestSessionTokenService;
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

                        // ---- the guest journey ----
                        //
                        // Deferred authentication: a visitor may describe a problem, have it
                        // classified, answer clarification questions, see who could take the job and
                        // when they are free, WITHOUT an account. Authentication is required at the
                        // moment a booking or an SOS request would be COMMITTED, and not before —
                        // see bookings.BookingsWebConfig and sos.SosWebConfig, which still gate
                        // every write.
                        //
                        // Each of these is a READ that produces no row and touches no other user's
                        // data:
                        //
                        //   classify           stateless; returns a category for some text. Costs an
                        //                      OpenAI call, which is why it — alone among these — is
                        //                      rate limited per IP (issues.IssuesWebConfig).
                        //   professionals      the marketplace listing for a category. Already
                        //                      public information: these are people advertising for
                        //                      work. The only customer-specific value it ever
                        //                      carried was a favourites count, which is 0 for a
                        //                      guest.
                        //   available-windows  a professional's free slots. Derived entirely from
                        //                      their own published working hours and existing
                        //                      bookings; it discloses no customer and no order.
                        //   professionals/{id} the public profile behind a listing card.
                        //
                        // What is deliberately NOT here: POST /api/issues, POST /api/bookings/orders,
                        // every /api/sos write, and everything under /api/users. Guests read; they
                        // do not write, and they cause no professional to be contacted.
                        .requestMatchers(HttpMethod.POST, "/api/issues/classify").permitAll()

                        // ---- guest image upload ----
                        //
                        // A visitor may photograph the leak before they have an account. Requiring
                        // one to attach a picture put a signup form between a person and the single
                        // most useful piece of evidence they have, on the screen where they are
                        // still deciding whether this product is for them.
                        //
                        // permitAll here does NOT mean anonymous, in exactly the sense backend MS9
                        // already established for GET /api/storage/images/**: authorization moved,
                        // it did not disappear. It moved to the handler, where
                        // auth.security.UploadOwnerResolver#requireIdentified answers 401 unless the
                        // caller presented EITHER a valid JWT OR a valid, unexpired guest-session
                        // token this backend minted itself. The filter chain cannot make that call,
                        // because "no Authorization header" is now a legitimate state rather than a
                        // rejection.
                        //
                        //   guest-sessions   mints that token. Creates no row, names no user, and
                        //                    grants nothing except the right to write into and read
                        //                    back one random namespace. Rate limited per IP in
                        //                    storage.StorageWebConfig, as is the upload itself for
                        //                    unauthenticated callers -- the account requirement used
                        //                    to be what bounded anonymous writes to the bucket.
                        //   images (POST)    the SAME upload, with the same content-type allow-list,
                        //                    the same 8 MB cap and the same key template. The
                        //                    CUSTOMER role gate still applies to anyone who does
                        //                    present a JWT (StorageWebConfig).
                        //   presigned-urls   re-resolves keys the caller already owns, so a paused
                        //                    guest draft can show its photos again on resume.
                        //
                        // Still NOT here, and still creating nothing: POST /api/issues. A guest's
                        // photos become an issue's photos only at the booking commit, once an
                        // account exists to own them.
                        .requestMatchers(HttpMethod.POST, "/api/storage/guest-sessions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/storage/images").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/storage/images/presigned-urls").permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/bookings/professionals").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/bookings/professionals/*/available-windows").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/professionals/*").permitAll()
                        //   reviews            the ratings and comments already shown on the
                        //                      profile behind a listing card. Choosing a tradesman
                        //                      is choosing based on what other people said about
                        //                      them, so putting that behind a signup form is the
                        //                      same wall as the rest of this block, on the screen
                        //                      where it does the most damage. Published content
                        //                      about a publicly listed professional; the response
                        //                      does not vary by who is asking.
                        //
                        // Scoped to GET and to the EXACT literal path. `/api/reviews/{id}` (PUT,
                        // DELETE) does not match this matcher, and POST /api/reviews does not
                        // match the method -- so all three writes still fall through to the
                        // authenticated catch-all AND to reviews.ReviewsWebConfig's CUSTOMER role
                        // gate, which is untouched.
                        .requestMatchers(HttpMethod.GET, "/api/reviews").permitAll()

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
        // X-Pronto-Guest-Session carries a guest's upload-namespace token (see
        // auth.security.GuestSessionTokenService). Without it in this list the browser's preflight
        // refuses the header and every guest upload fails before it is sent -- and it cannot ride
        // in Authorization, which is reserved for the JWT a guest does not have and a
        // just-registered customer sends alongside it.
        configuration.setAllowedHeaders(List.of("Content-Type", "Authorization",
                GuestSessionTokenService.HEADER));
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
