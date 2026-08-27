package com.pronto.auth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production CORS allow-list, evaluated through Spring's real CORS decision path.
 *
 * <p>{@link CorsOriginStartupGuardTest} covers the shapes the guard REFUSES to start with (empty,
 * wildcard, localhost, plaintext). That is a different question from what a running application
 * does with a valid list: a guard that accepts {@code https://prontohomeservice.com} does not prove
 * a request from that origin is answered, nor that one from somewhere else is not.
 *
 * <p><b>Why {@link DefaultCorsProcessor} rather than an HTTP call.</b> Driving a preflight through
 * {@code TestRestTemplate} does not produce one: the response comes back {@code 200} with
 * {@code Allow: POST,OPTIONS} and no {@code Access-Control-Allow-Origin}, because the request is
 * answered by the DispatcherServlet's default {@code OPTIONS} handling rather than treated as a
 * preflight. Asserting on that would have tested the HTTP client, and would have passed just as
 * happily against a misconfigured allow-list. {@code DefaultCorsProcessor} is the component Spring
 * Security actually delegates the decision to, fed from the application's own
 * {@link CorsConfigurationSource} bean, so this exercises the real rule with no client in the way.
 *
 * <p><b>Both production origins are allowed on purpose.</b> CloudFront serves the apex and
 * {@code www} as two aliases of one distribution with no redirect between them, so the application
 * genuinely executes from both and the browser sends whichever the user typed. Allowing only the
 * apex would leave {@code www} loading the SPA and then failing every API call — a blank site that
 * looks deployed. This matches real routing rather than broadening the list.
 */
@EnabledIf("postgresAvailable")
@SpringBootTest(properties = {
        "pronto.cors.allowed-origins=https://prontohomeservice.com,https://www.prontohomeservice.com"
})
class ProductionCorsOriginTest {

    private static final String APEX = "https://prontohomeservice.com";
    private static final String WWW = "https://www.prontohomeservice.com";
    /** Deliberately a lookalike: a substring check or careless pattern would let this through. */
    private static final String STRANGER = "https://prontohomeservice.com.attacker.example";

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    private final DefaultCorsProcessor processor = new DefaultCorsProcessor();

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean postgresAvailable() {
        String host = System.getenv().getOrDefault("DB_HOST", "localhost");
        String port = System.getenv().getOrDefault("DB_PORT", "5433");
        String name = System.getenv().getOrDefault("DB_NAME", "pronto");
        String user = System.getenv().getOrDefault("DB_USER", "pronto");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "pronto");
        try (Connection ignored = DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/" + name, user, password)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs a genuine preflight for {@code origin} through the application's own CORS rule. */
    private MockHttpServletResponse preflight(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/auth/login");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "content-type");

        MockHttpServletResponse response = new MockHttpServletResponse();
        CorsConfiguration configuration = corsConfigurationSource.getCorsConfiguration(request);
        assertThat(configuration)
                .as("/api/** must be covered by a CORS configuration")
                .isNotNull();

        try {
            processor.processRequest(configuration, request, response);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return response;
    }

    @Test
    void theApexOrigin_isAllowed() {
        MockHttpServletResponse response = preflight(APEX);

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(APEX);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void theWwwOrigin_isAllowed_becauseCloudFrontServesItToo() {
        MockHttpServletResponse response = preflight(WWW);

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(WWW);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void anArbitraryExternalOrigin_isRefused() {
        MockHttpServletResponse response = preflight(STRANGER);

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void noWildcardIsEverEmitted() {
        assertThat(preflight(APEX).getHeader("Access-Control-Allow-Origin")).isNotEqualTo("*");

        CorsConfiguration configuration = corsConfigurationSource.getCorsConfiguration(
                new MockHttpServletRequest("POST", "/api/auth/login"));
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).doesNotContain("*");
    }

    @Test
    void theStompHandshakeUsesTheSameAllowList_notAWiderOne() {
        // realtime.config.WebSocketConfig reads pronto.cors.allowed-origins exactly as
        // SecurityConfig does. Pinning the shared list is what stops a future change from relaxing
        // one and not the other -- the SOS realtime channel carries the same session as the REST
        // API, so a wider handshake rule would be a wider authentication surface.
        CorsConfiguration configuration = corsConfigurationSource.getCorsConfiguration(
                new MockHttpServletRequest("POST", "/api/auth/login"));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactlyInAnyOrder(APEX, WWW);
    }
}
