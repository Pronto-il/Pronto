package com.pronto.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.dto.ImageAttachment;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OpenAI transport's contract, exercised without touching the network — the request this
 * client builds and the envelope handling it applies to what comes back.
 *
 * <p>Constructing the client does not open a connection (the {@code RestClient} is built
 * eagerly but dials nothing), so the request-shaping and response-unwrapping halves are
 * directly testable. Only the actual HTTP call is not, and that is what the opt-in
 * {@code eval.OpenAiClassificationEvaluationRunnerTest} covers against the real API.
 */
class OpenAiChatClientTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("primaryCategoryCode", Map.of("type", "string")),
            "required", List.of("primaryCategoryCode"),
            "additionalProperties", false);

    private ObjectMapper objectMapper;
    private OpenAiChatClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        client = new OpenAiChatClient("sk-test-not-a-real-key", "gpt-4o-mini", 10_000, objectMapper);
    }

    private Map<String, Object> requestBody() {
        return client.buildRequestBody("system prompt", "evidence prompt", List.of(),
                "pronto_issue_routing", SCHEMA);
    }

    /**
     * The parameter that actually decides whether repeat runs are comparable. Left unset the
     * API defaults to 1.0, which is sampling variety on a task that has one right answer.
     */
    @Test
    void decodingIsPinnedToBeDeterministic() {
        Map<String, Object> body = requestBody();

        assertThat(body).containsEntry("temperature", 0.0);
        assertThat(body).containsKey("seed");
    }

    @Test
    void theResponseFormatIsAStrictJsonSchemaSoTheModelCannotReturnAFreeShape() {
        Map<String, Object> body = requestBody();

        @SuppressWarnings("unchecked")
        Map<String, Object> responseFormat = (Map<String, Object>) body.get("response_format");
        assertThat(responseFormat).containsEntry("type", "json_schema");

        @SuppressWarnings("unchecked")
        Map<String, Object> jsonSchema = (Map<String, Object>) responseFormat.get("json_schema");
        assertThat(jsonSchema).containsEntry("strict", true);
        assertThat(jsonSchema).containsEntry("name", "pronto_issue_routing");
        assertThat(jsonSchema).containsEntry("schema", SCHEMA);
    }

    @Test
    void theSystemPromptAndTheCustomerEvidenceTravelAsSeparateMessages() {
        // The separation is the structural half of the injection defence: customer text is
        // only ever the user message, never merged into the system instructions.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) requestBody().get("messages");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("role", "system").containsEntry("content", "system prompt");
        assertThat(messages.get(1)).containsEntry("role", "user");
    }

    @Test
    void imagesRideInlineAsDataUrlsAlongsideTheEvidenceText() {
        ImageAttachment image = ImageAttachment.of("issues/1/photo.jpg", new byte[]{1, 2, 3}, "image/jpeg");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) client.buildRequestBody(
                "system", "evidence", List.of(image), "schema", SCHEMA).get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(1).get("content");

        assertThat(content).hasSize(2);
        assertThat(content.get(0)).containsEntry("type", "text");
        assertThat(content.get(1)).containsEntry("type", "image_url");
    }

    /** An empty attachment is skipped rather than sent as an empty data URL, which the API rejects. */
    @Test
    void anAttachmentWithNoContentIsSkippedRatherThanSentEmpty() {
        ImageAttachment empty = ImageAttachment.of("issues/1/broken.jpg", new byte[0], "image/jpeg");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) client.buildRequestBody(
                "system", "evidence", List.of(empty), "schema", SCHEMA).get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(1).get("content");

        assertThat(content).hasSize(1);
        assertThat(content.get(0)).containsEntry("type", "text");
    }

    // -- response envelope handling ----------------------------------------------------------

    @Test
    void aValidStructuredResponseIsUnwrappedFromTheChatCompletionEnvelope() throws Exception {
        String raw = """
                {"choices":[{"message":{"content":"{\\"primaryCategoryCode\\":\\"plumbing\\"}"}}]}""";

        JsonNode payload = client.extractPayload(raw);

        assertThat(payload.path("primaryCategoryCode").asText()).isEqualTo("plumbing");
    }

    @Test
    void aMessageBodyThatIsNotValidJsonIsRejectedRatherThanGuessedAt() {
        String raw = """
                {"choices":[{"message":{"content":"sorry, I cannot help with that"}}]}""";

        assertThatThrownBy(() -> client.extractPayload(raw))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void aResponseWithNoMessageContentIsAServiceError() {
        assertThatThrownBy(() -> client.extractPayload("""
                {"choices":[{"message":{}}]}"""))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(ErrorCode.AI_SERVICE_ERROR);
    }

    @Test
    void aBlankMessageContentIsAServiceErrorRatherThanAnEmptyClassification() {
        assertThatThrownBy(() -> client.extractPayload("""
                {"choices":[{"message":{"content":"   "}}]}"""))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anEnvelopeWithNoChoicesAtAllIsAServiceError() {
        assertThatThrownBy(() -> client.extractPayload("""
                {"choices":[]}"""))
                .isInstanceOf(ApiException.class);
    }

    /** An API-level error body must not be mistaken for a classification. */
    @Test
    void anErrorPayloadFromTheProviderIsNotTreatedAsAResult() {
        assertThatThrownBy(() -> client.extractPayload("""
                {"error":{"message":"Rate limit reached","type":"rate_limit_error"}}"""))
                .isInstanceOf(ApiException.class);
    }
}
