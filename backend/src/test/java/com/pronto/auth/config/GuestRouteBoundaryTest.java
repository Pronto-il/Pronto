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
            // Choosing a tradesman is choosing on what other people said about them. This one was
            // missed when the rest of the block was written, and the symptom was a 401 on every
            // professional profile a guest opened.
            "/api/reviews",
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

    @Test
    void publishingAReviewIsStillGated() throws IOException {
        // Reading reviews became public; writing them did not. The permit line is METHOD-scoped, so
        // POST /api/reviews and PUT/DELETE /api/reviews/{id} still fall through to the authenticated
        // catch-all AND to ReviewsWebConfig's CUSTOMER gate. A path-only permitAll would take all
        // three with it, which is the mistake this asserts against.
        assertThat(securityConfigSource())
                .contains(".requestMatchers(HttpMethod.GET, \"/api/reviews\").permitAll()")
                .doesNotContain(".requestMatchers(\"/api/reviews\").permitAll()")
                .doesNotContain("\"/api/reviews/*\").permitAll()");
        assertThat(webConfigSource("reviews", "ReviewsWebConfig"))
                .contains("RoleRequiredInterceptor(UserRole.CUSTOMER.name(), \"POST\")")
                .contains("RoleRequiredInterceptor(UserRole.CUSTOMER.name())");
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

    // ---- guest image upload: the one write route deliberately on the public side ----

    @ParameterizedTest(name = "{0} is reachable without a JWT")
    @ValueSource(strings = {
            "/api/storage/guest-sessions",
            "/api/storage/images",
            "/api/storage/images/presigned-urls",
    })
    void theGuestUploadRoutesArePermitted(String path) throws IOException {
        // A visitor may photograph the leak before they have an account. These are the only routes
        // on the public side of the line that touch storage.
        assertThat(securityConfigSource())
                .as("%s must be permitAll so a guest can attach a photo", path)
                .contains("\"" + path + "\").permitAll()");
    }

    @Test
    void theUploadRouteIsPermittedButNotAnonymous() throws IOException {
        // This is the assertion that makes the permitAll above safe rather than alarming: the route
        // authorizes in the handler instead of the filter chain, because "no Authorization header"
        // is now a legitimate state. Deleting requireIdentified would turn POST /api/storage/images
        // into a genuinely open upload endpoint, and nothing in SecurityConfig would notice.
        assertThat(controllerSource("storage", "StorageController"))
                .as("the upload handler must refuse a caller who proved neither identity")
                .contains("uploadOwnerResolver.requireIdentified(principal, guestSessionToken)");
        assertThat(controllerSource("storage", "StorageController"))
                .as("the batch presign handler must do the same")
                .containsPattern("presignedUrls\\([\\s\\S]*?requireIdentified");
    }

    @Test
    void anonymousUploadsAreRateLimitedAndAuthenticatedOnesAreNot() throws IOException {
        // Opening uploads to callers with no account removes the bound that used to exist (you had
        // to be a registered, phone-verified customer). Per-source limiting replaces it -- and the
        // `true` flag is what keeps it off existing customers, whose upload behaviour must not
        // change at all.
        String source = webConfigSource("storage", "StorageWebConfig");
        assertThat(source).contains("AuthRateLimitInterceptor");
        assertThat(source).contains("\"/api/storage/images\"");
        assertThat(source).contains("clientIpResolver, true)");
    }

    @Test
    void theCustomerRoleGateOnUploadsSurvivesForCallersWhoPresentAJwt() throws IOException {
        // Allowing anonymous through must not have quietly let a PROFESSIONAL token upload an issue
        // photo -- an outcome the storage package has an explicit, previously-fixed bug about.
        assertThat(webConfigSource("storage", "StorageWebConfig"))
                .contains("new RoleRequiredInterceptor(UserRole.CUSTOMER.name(), true)")
                .contains("\"/api/storage/images\"");
    }

    @Test
    void creatingAnIssueFromAGuestSessionIsStillNotPossible() throws IOException {
        // The guest-session header appears on POST /api/issues, but only as the CLAIM on existing
        // image keys. The route itself stays CUSTOMER-gated, so the header can never be the sole
        // credential on a write.
        assertThat(securityConfigSource()).doesNotContain("\"/api/issues\").permitAll()");
        assertThat(controllerSource("issues", "IssuesController")).contains("GuestSessionTokenService.HEADER");
        assertThat(webConfigSource("issues", "IssuesWebConfig")).contains("\"/api/issues\"");
    }

    private static String controllerSource(String pkg, String className) throws IOException {
        return Files.readString(
                Path.of("src/main/java/com/pronto/" + pkg + "/controller/" + className + ".java"),
                StandardCharsets.UTF_8);
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
