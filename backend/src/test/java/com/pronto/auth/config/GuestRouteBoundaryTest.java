package com.pronto.auth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the authentication boundary sits, asserted as a boundary rather than route by route.
 *
 * <p>Deferred authentication moved a wall that had stood at the start of the funnel to the two
 * points where something is committed. The risk that creates is not that a guest route stops
 * working — that is visible on the first visit — it is that a WRITE route quietly joins the
 * public side, or that a future route is added and nobody notices which side of the line it
 * landed on. Both failures are silent and both are serious: one lets an anonymous caller create
 * an order, the other lets one make a professional's phone ring.
 *
 * <p>These are source-level assertions on the two configuration classes that define the boundary.
 * That is deliberate: a Spring slice test would prove the beans wire up, while the property that
 * actually matters is which literal paths were written down.
 */
class GuestRouteBoundaryTest {

    private static final Path SECURITY_CONFIG =
            Path.of("src/main/java/com/pronto/auth/config/SecurityConfig.java");

    private static String securityConfigSource() throws IOException {
        return Files.readString(SECURITY_CONFIG, StandardCharsets.UTF_8);
    }

    // ---- 1, 2, 3, 4. The guest journey is reachable ----

    @ParameterizedTest(name = "{0} is public")
    @ValueSource(strings = {
            "/api/issues/classify",
            "/api/bookings/professionals",
            "/api/bookings/professionals/*/available-windows",
            "/api/professionals/*",
    })
    void theGuestJourneyRoutesArePermitted(String path) throws IOException {
        assertThat(securityConfigSource())
                .as("%s must be permitAll so a visitor can get as far as the booking button", path)
                .contains("\"" + path + "\").permitAll()");
    }

    @Test
    void everythingElseStillRequiresAuthentication() throws IOException {
        // The catch-all is what makes the list above exhaustive rather than indicative: a route
        // that is not named is authenticated by default, which is the safe direction for anything
        // added later.
        assertThat(securityConfigSource()).contains(".anyRequest().authenticated()");
    }

    // ---- 5, 14, 16. The commit points are NOT reachable ----

    @Test
    void creatingAnIssueIsNotPublic() throws IOException {
        assertThat(securityConfigSource()).doesNotContain("\"/api/issues\").permitAll()");
        assertThat(webConfigSource("issues", "IssuesWebConfig")).contains("\"/api/issues\"");
    }

    @Test
    void creatingAnOrderIsStillCustomerGated() throws IOException {
        // The standard-booking commit: writes an order row and notifies a professional.
        assertThat(webConfigSource("bookings", "BookingsWebConfig"))
                .contains("RoleRequiredInterceptor(UserRole.CUSTOMER.name())")
                .contains("\"/api/bookings/orders\"");
    }

    @Test
    void everySosCustomerRouteIsStillGated() throws IOException {
        // The SOS commit is the same call as the notification -- SosService.activate dispatches
        // synchronously -- so this gate is the ONLY thing standing between an anonymous request
        // and a real professional being contacted.
        assertThat(webConfigSource("sos", "SosWebConfig"))
                .contains("RoleRequiredInterceptor(UserRole.CUSTOMER.name())")
                .contains("\"/api/sos/requests\"");
    }

    @ParameterizedTest(name = "{0} must never be public")
    @ValueSource(strings = {
            "/api/bookings/orders",
            "/api/sos/requests",
            "/api/users/me",
    })
    void noWriteRouteIsPubliclyReachable(String path) throws IOException {
        assertThat(securityConfigSource())
                .as("%s commits something or exposes a customer's own records", path)
                .doesNotContain("\"" + path + "\").permitAll()");
    }

    // ---- the classify endpoint is public AND paid, so it must be limited ----

    @Test
    void theOnlyPublicRouteThatSpendsMoneyIsRateLimited() throws IOException {
        // classify is unauthenticated and calls OpenAI on every request, which nothing else in this
        // API is. Without a limiter, opening it converts a signup wall into a billing surface.
        String source = webConfigSource("issues", "IssuesWebConfig");

        assertThat(source).contains("AuthRateLimitInterceptor");
        assertThat(source).contains("\"/api/issues/classify\"");
    }

    @Test
    void classifyIsNoLongerBehindTheCustomerRoleGate() throws IOException {
        // The specific line this change removed. Asserted as an absence because that is what
        // "a guest may classify" actually is -- and an absence is exactly the kind of thing a
        // later edit restores without meaning to.
        String source = webConfigSource("issues", "IssuesWebConfig");
        // Anchored on the REGISTRATION of each interceptor, not the first mention of its name --
        // the rate limiter is also named in an import at the top of the file, which sorts before
        // the role gate and would invert the range.
        int gate = source.indexOf("new RoleRequiredInterceptor(UserRole.CUSTOMER.name())");
        int limiter = source.indexOf("new AuthRateLimitInterceptor(");
        assertThat(gate).as("the customer role gate must still be registered").isPositive();
        assertThat(limiter).as("the rate limiter must be registered after it").isGreaterThan(gate);
        assertThat(source.substring(gate, limiter))
                .as("classify must not be inside the role-gated path list")
                .doesNotContain("/api/issues/classify");
    }

    private static String webConfigSource(String pkg, String className) throws IOException {
        return Files.readString(
                Path.of("src/main/java/com/pronto/" + pkg + "/config/" + className + ".java"),
                StandardCharsets.UTF_8);
    }
}
