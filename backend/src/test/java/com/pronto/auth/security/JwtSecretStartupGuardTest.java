package com.pronto.auth.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The signing key for every JWT this platform issues.
 *
 * <p>The placeholder check dates from Milestone 7 hardening and had no test of its own. Production
 * MS4 adds the two cases it did not cover — an empty override and one that is too short for HS256 —
 * and this class covers all three together, since they are one question asked three ways.
 *
 * <p><b>Why the rule here is {@code != local} rather than
 * {@code ProntoEnvironment.isProductionLike()}</b>, unlike every other MS4 guard: see
 * {@code common.config.ProntoEnvironment}'s Javadoc. A publicly-known signing key is directly
 * exploitable by anyone who can reach the application, whatever the environment is called, so
 * {@code demo} and {@code test} are held to the same standard as production. Loosening it to match
 * the shared predicate would be a security regression.
 */
class JwtSecretStartupGuardTest {

    private static final String REAL_SECRET = "a-real-jwt-signing-key-of-more-than-thirty-two-characters";

    private static JwtSecretStartupGuard guard(String secret, String environment) {
        return new JwtSecretStartupGuard(secret, environment);
    }

    // ---- the checked-in placeholder ----

    @ParameterizedTest(name = "pronto.environment={0} rejects the placeholder secret")
    @ValueSource(strings = {"production", "staging", "demo", "test", "prod-eu", "typo"})
    void anyNonLocalEnvironment_withThePlaceholderSecret_refusesToStart(String environment) {
        assertThatThrownBy(() ->
                guard(JwtSecretStartupGuard.INSECURE_DEFAULT_SECRET, environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("placeholder");
    }

    @Test
    void local_withThePlaceholderSecret_starts() {
        // The zero-configuration development path. Breaking this breaks every first run.
        assertThatCode(() ->
                guard(JwtSecretStartupGuard.INSECURE_DEFAULT_SECRET, "local").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void localIsMatchedCaseInsensitively() {
        assertThatCode(() ->
                guard(JwtSecretStartupGuard.INSECURE_DEFAULT_SECRET, "LOCAL").validate())
                .doesNotThrowAnyException();
    }

    // ---- Production MS4: empty and weak overrides ----

    @Test
    void production_withAnEmptySecret_refusesToStart() {
        // JWT_SECRET= is not "unset": the environment variable exists, so the application.yml
        // default never applies and the placeholder check above never fires.
        assertThatThrownBy(() -> guard("", "production").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("empty");
    }

    @Test
    void production_withAWhitespaceOnlySecret_refusesToStart() {
        assertThatThrownBy(() -> guard("      ", "production").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void production_withASecretTooShortForHs256_refusesToStart() {
        // Without this the same configuration still fails closed — but inside jjwt's
        // Keys.hmacShaKeyFor, with a WeakKeyException that names no environment variable.
        assertThatThrownBy(() -> guard("hunter2", "production").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("32");
    }

    @Test
    void thirtyOneCharacters_isRefused_andThirtyTwoIsAccepted() {
        assertThatThrownBy(() -> guard("x".repeat(31), "production").validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> guard("x".repeat(32), "production").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void failureMessage_neverContainsTheSecret() {
        assertThatThrownBy(() -> guard("short-but-real", "production").validate())
                .hasMessageNotContaining("short-but-real");
    }

    // ---- the positive case ----

    @Test
    void structurallyValidProductionConfiguration_passes() {
        assertThatCode(() -> guard(REAL_SECRET, "production").validate()).doesNotThrowAnyException();
    }
}
