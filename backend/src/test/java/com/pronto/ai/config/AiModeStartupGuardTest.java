package com.pronto.ai.config;

import com.pronto.common.config.ProntoEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production MS4 — roadmap rule §1.6 ("never silently run with development behaviour") applied to
 * the one provider Production MS1/MS2 did not reach.
 *
 * <p>The failure this prevents is the quietest of the four. {@code MockAiClassificationClient}
 * answers every request, in the right shape, with plausible confidences — so the routing pipeline,
 * the clarification flow and the telemetry all behave normally while every category on every order
 * came from a keyword table. Nothing crashes and nothing looks wrong.
 *
 * <p>Unit tests by direct construction, matching {@code auth.config.ProviderModeStartupGuardTest}:
 * no Spring context, no network, no OpenAI key.
 */
class AiModeStartupGuardTest {

    private static final String REAL_KEY = "a-structurally-valid-openai-api-key";
    private static final String REAL_MODEL = "gpt-4o-mini";

    private static AiModeStartupGuard guard(String environment, String mode, String apiKey, String model) {
        return new AiModeStartupGuard(new ProntoEnvironment(environment), mode, apiKey, model);
    }

    // ---- production-like environments may not run the mock ----

    @Test
    void production_withMockAi_refusesToStart() {
        assertThatThrownBy(() -> guard("production", "mock", "", REAL_MODEL).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pronto.ai.mode=mock")
                .hasMessageContaining("AI_MODE");
    }

    @ParameterizedTest(name = "pronto.environment={0} is production-like")
    @ValueSource(strings = {"production", "prod", "staging", "prod-eu", "PRODUCTION", "typo"})
    void anyUnrecognizedEnvironment_withMockAi_refusesToStart(String environment) {
        // The allow-list direction, verified here as well as in ProntoEnvironment's own tests: a
        // misspelled PRONTO_ENVIRONMENT must make the guards stricter, never switch them off.
        assertThatThrownBy(() -> guard(environment, "mock", "", REAL_MODEL).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_MODE");
    }

    @ParameterizedTest(name = "pronto.environment={0} may run the mock")
    @ValueSource(strings = {"local", "test", "demo", "LOCAL"})
    void nonProductionEnvironments_mayRunTheMock(String environment) {
        // Local development must keep working with zero configuration — no OpenAI key, no network.
        assertThatCode(() -> guard(environment, "mock", "", "").validate()).doesNotThrowAnyException();
    }

    // ---- mode/credential consistency, in every environment ----

    @Test
    void openAiModeWithoutAKey_refusesToStart_evenLocally() {
        // Not environment-scoped, for the reason ProviderModeStartupGuard's MAPS_API_KEY check is
        // not: this is not a degraded mode, it is one in which every request is rejected.
        assertThatThrownBy(() -> guard("local", "openai", "", REAL_MODEL).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void openAiModeWithoutAModel_refusesToStart() {
        assertThatThrownBy(() -> guard("production", "openai", REAL_KEY, "   ").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_MODEL");
    }

    @Test
    void failureMessage_neverContainsTheApiKey() {
        assertThatThrownBy(() -> guard("production", "openai", REAL_KEY, "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(REAL_KEY);
    }

    // ---- unrecognized modes ----

    @ParameterizedTest(name = "AI_MODE={0}")
    @ValueSource(strings = {"openai_", "OpenAI-mini", "off", " "})
    void unrecognizedMode_refusesToStartWithAMessageNamingTheVariable(String mode) {
        assertThatThrownBy(() -> guard("local", mode, REAL_KEY, REAL_MODEL).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_MODE")
                .hasMessageContaining("not a recognized mode");
    }

    @Test
    void modeIsCaseInsensitive_matchingSpringsOwnConditionalOnProperty() {
        // @ConditionalOnProperty compares with equalsIgnoreCase, so AI_MODE=OPENAI really does
        // select the OpenAI client. The guard must agree, or it would refuse a working config.
        assertThatCode(() -> guard("production", "OPENAI", REAL_KEY, REAL_MODEL).validate())
                .doesNotThrowAnyException();
    }

    // ---- the positive case ----

    @Test
    void structurallyValidProductionConfiguration_passes() {
        assertThatCode(() -> guard("production", "openai", REAL_KEY, REAL_MODEL).validate())
                .doesNotThrowAnyException();
    }
}
