package com.pronto.auth.config;

import com.pronto.common.config.ProntoEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two production settings whose absence is silent, both found by the MS1 pre-DONE audit.
 *
 * <p>Neither failure announces itself at runtime: a placeholder OTP pepper still hashes, and an empty
 * {@code TRUSTED_PROXIES} still rate-limits — just with a public key and a single shared bucket
 * respectively. Startup is the only place either can be caught before users are affected.
 */
class ProductionHardeningStartupGuardTest {

    private static final String REAL_PEPPER = "a-real-server-side-otp-pepper-of-sufficient-length";
    private static final String PLACEHOLDER_PEPPER =
            "local-dev-only-insecure-otp-pepper-please-override-via-OTP_PEPPER-env-var-before-any-real-deployment";

    private static ProductionHardeningStartupGuard guard(String environment, String pepper,
                                                          String trustedProxies, boolean behindProxy) {
        return new ProductionHardeningStartupGuard(
                new ProntoEnvironment(environment), pepper, trustedProxies, behindProxy);
    }

    // ---- the OTP pepper ----

    @Test
    void production_withThePlaceholderPepper_refusesToStart() {
        // The placeholder is checked into this repository, so booting with it means every stored OTP
        // is recoverable by anyone who can read the source.
        assertThatThrownBy(() -> guard("production", PLACEHOLDER_PEPPER, "10.0.0.0/16", true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OTP_PEPPER")
                .hasMessageContaining("placeholder");
    }

    @Test
    void production_withNoPepperAtAll_refusesToStart() {
        assertThatThrownBy(() -> guard("production", "", "10.0.0.0/16", true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OTP_PEPPER");
    }

    @Test
    void production_withATooShortPepper_refusesToStart() {
        assertThatThrownBy(() -> guard("production", "short", "10.0.0.0/16", true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than");
    }

    @Test
    void production_withARealPepperAndTrustedProxies_starts() {
        assertThatCode(() -> guard("production", REAL_PEPPER, "10.0.0.0/16", true).validate())
                .doesNotThrowAnyException();
    }

    // ---- TRUSTED_PROXIES ----

    @Test
    void production_behindAProxyWithNoTrustedProxies_refusesToStart() {
        // Safe against spoofing, but an outage: every user collapses into the load balancer's single
        // rate-limit bucket and registration is capped platform-wide.
        assertThatThrownBy(() -> guard("production", REAL_PEPPER, "", true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRUSTED_PROXIES")
                .hasMessageContaining("behind-proxy");
    }

    @Test
    void production_notBehindAProxy_mayLeaveTrustedProxiesEmpty() {
        // The escape hatch exists so that "we are reached directly" is a decision somebody wrote
        // down, rather than something inferred from an empty string.
        assertThatCode(() -> guard("production", REAL_PEPPER, "", false).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void bothFailuresAreReportedTogether_notOneStartupAtATime() {
        assertThatThrownBy(() -> guard("production", PLACEHOLDER_PEPPER, "", true).validate())
                .hasMessageContaining("OTP_PEPPER")
                .hasMessageContaining("TRUSTED_PROXIES");
    }

    // ---- environment scoping ----

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "demo"})
    void developmentEnvironmentsAreUnaffected(String environment) {
        // No existing local or CI startup path changes: the placeholder pepper and an empty
        // TRUSTED_PROXIES are exactly what those environments run with.
        assertThatCode(() -> guard(environment, PLACEHOLDER_PEPPER, "", true).validate())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"staging", "prod-eu", "prdouction", "anything-nobody-thought-of"})
    void everyUnrecognizedEnvironmentIsTreatedAsProduction(String environment) {
        // Staging in particular: the whole point of a production-like environment is that it
        // validates production behaviour, which it cannot do with a public key.
        assertThatThrownBy(() -> guard(environment, PLACEHOLDER_PEPPER, "", true).validate())
                .isInstanceOf(IllegalStateException.class);
    }
}
