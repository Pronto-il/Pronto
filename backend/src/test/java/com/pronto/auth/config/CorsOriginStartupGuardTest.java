package com.pronto.auth.config;

import com.pronto.common.config.ProntoEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production MS4 — {@code CORS_ALLOWED_ORIGINS} must not still be pointing at a developer's laptop.
 *
 * <p>The default in {@code application.yml} is {@code http://localhost:5173}, which is correct for
 * the only environment that existed when it was written and is a deployment defect anywhere else.
 * Its symptom is "the frontend is broken" with nothing in any log to say why, so startup is the
 * only place it can usefully be caught.
 */
class CorsOriginStartupGuardTest {

    private static CorsOriginStartupGuard guard(String environment, String... origins) {
        return new CorsOriginStartupGuard(new ProntoEnvironment(environment), List.of(origins));
    }

    // ---- development origins ----

    @ParameterizedTest(name = "{0} is a development origin")
    @ValueSource(strings = {
            "http://localhost:5173",
            "https://localhost:5173",
            "http://127.0.0.1:5173",
            "https://127.0.0.1",
            "http://0.0.0.0:8080",
            "http://[::1]:5173"})
    void production_withADevelopmentOrigin_refusesToStart(String origin) {
        assertThatThrownBy(() -> guard("production", origin).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS_ALLOWED_ORIGINS")
                .hasMessageContaining(origin);
    }

    @Test
    void production_withOneGoodOriginAndOneDevelopmentOrigin_stillRefuses() {
        // Not "does the list contain a valid origin" — every entry is a grant, so every entry counts.
        assertThatThrownBy(() ->
                guard("production", "https://app.example.com", "http://localhost:5173").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost");
    }

    // ---- wildcards ----

    @ParameterizedTest(name = "{0} is a wildcard origin")
    @ValueSource(strings = {"*", "https://*.example.com", "*.example.com"})
    void production_withAWildcardOrigin_refusesToStart(String origin) {
        assertThatThrownBy(() -> guard("production", origin).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }

    // ---- scheme ----

    @Test
    void production_withAPlaintextOrigin_refusesToStart() {
        assertThatThrownBy(() -> guard("production", "http://app.example.com").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-HTTPS");
    }

    // ---- empty ----

    @Test
    void production_withNoOriginsAtAll_refusesToStart() {
        assertThatThrownBy(() -> guard("production").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void production_withABlankOriginEntry_refusesToStart() {
        // CORS_ALLOWED_ORIGINS="" binds to a single empty element rather than to an empty list.
        assertThatThrownBy(() -> guard("production", "   ").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    // ---- dev/test convenience is untouched ----

    @ParameterizedTest(name = "pronto.environment={0} keeps the Vite dev origin")
    @ValueSource(strings = {"local", "test", "demo"})
    void nonProductionEnvironments_keepTheDevelopmentDefault(String environment) {
        assertThatCode(() -> guard(environment, "http://localhost:5173").validate())
                .doesNotThrowAnyException();
    }

    // ---- the positive case ----

    @Test
    void structurallyValidProductionConfiguration_passes() {
        assertThatCode(() ->
                guard("production", "https://app.pronto.example", "https://www.pronto.example").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void productionOriginWithAnExplicitPort_passes() {
        assertThatCode(() -> guard("production", "https://app.pronto.example:8443").validate())
                .doesNotThrowAnyException();
    }
}
