package com.pronto.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which models get a {@code temperature}, and which must not.
 *
 * <p>Verified live against the API before this rule was written:
 *
 * <pre>
 *   gpt-5-mini + temperature=0.0  ->  HTTP 400 unsupported_value
 *   "Unsupported value: 'temperature' does not support 0.0 with this model.
 *    Only the default (1) value is supported."
 * </pre>
 *
 * <p>That is a permanent 4xx, which {@code isRetryable} correctly refuses to retry — so getting
 * this wrong is not a subtle quality regression, it is every classification failing on the first
 * attempt. Hence a test rather than a comment.
 */
class OpenAiTemperatureCompatibilityTest {

    private static Map<String, Object> bodyFor(String model) {
        return new OpenAiChatClient(RestClientStub.unused(), model, new ObjectMapper(), millis -> true)
                .buildRequestBody("system", "evidence", List.of(), "schema",
                        Map.of("type", "object"));
    }

    @ParameterizedTest(name = "{0} rejects a custom temperature, so none is sent")
    @ValueSource(strings = {"gpt-5-mini", "gpt-5", "gpt-5-nano", "GPT-5-Mini", "o1", "o3-mini", "o4-mini"})
    void reasoningModelsGetNoTemperature(String model) {
        assertThat(OpenAiChatClient.supportsCustomTemperature(model)).isFalse();
        assertThat(bodyFor(model))
                .as("sending temperature to %s would fail every request with a non-retryable 400", model)
                .doesNotContainKey("temperature");
    }

    @ParameterizedTest(name = "{0} keeps temperature 0")
    @ValueSource(strings = {"gpt-4.1-mini", "gpt-4o-mini", "gpt-4o", "gpt-4.1"})
    void nonReasoningModelsKeepGreedyDecoding(String model) {
        // The regression that matters in the other direction. Temperature 0 is what stopped the
        // same evaluation set scoring 98.4% and 95.2% on consecutive runs; dropping it for every
        // model "to be safe" would silently reintroduce that noise on the models that accept it.
        assertThat(OpenAiChatClient.supportsCustomTemperature(model)).isTrue();
        assertThat(bodyFor(model)).containsEntry("temperature", 0.0);
    }

    @Test
    void seedAndStructuredOutputSurviveOnEveryModel() {
        // seed IS accepted by gpt-5-mini (verified live) and is the only determinism lever left
        // once temperature is gone, so it must not be dropped along with it.
        for (String model : List.of("gpt-5-mini", "gpt-4.1-mini")) {
            Map<String, Object> body = bodyFor(model);
            assertThat(body).containsKey("seed");
            assertThat(body).containsKey("response_format");

            @SuppressWarnings("unchecked")
            Map<String, Object> responseFormat = (Map<String, Object>) body.get("response_format");
            assertThat(responseFormat).containsEntry("type", "json_schema");

            @SuppressWarnings("unchecked")
            Map<String, Object> jsonSchema = (Map<String, Object>) responseFormat.get("json_schema");
            assertThat(jsonSchema).containsEntry("strict", true);
            assertThat(jsonSchema).containsEntry("name", "schema");
        }
    }

    @Test
    void anUnknownModelKeepsTheExistingBehaviour() {
        // Fail towards what works today: an unrecognised name is assumed to accept temperature,
        // because that is true of every model this application has ever used and the alternative
        // would silently un-pin decoding for a model nobody has checked.
        assertThat(OpenAiChatClient.supportsCustomTemperature("some-future-model")).isTrue();
        assertThat(OpenAiChatClient.supportsCustomTemperature(null)).isTrue();
    }

    /** Minimal stand-in — {@link #bodyFor} never performs a request. */
    private static final class RestClientStub {
        static org.springframework.web.client.RestClient unused() {
            return org.springframework.web.client.RestClient.create();
        }
    }
}
