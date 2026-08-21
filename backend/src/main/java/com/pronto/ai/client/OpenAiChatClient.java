package com.pronto.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.dto.ImageAttachment;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The OpenAI transport: one place that knows how to send a system prompt, a multimodal user
 * message and a strict JSON Schema to {@code /v1/chat/completions}, retry, and hand back the
 * parsed structured payload.
 *
 * <p>Both AI responsibilities (routing and the Professional Brief) go through here, so
 * timeouts, retries, image encoding and "the response envelope was not what we expected"
 * handling exist once rather than twice.
 *
 * <p>Never logs the API key, prompt bodies or image bytes — only sizes, the schema name and
 * failure messages.
 */
@Component
@ConditionalOnProperty(prefix = "pronto.ai", name = "mode", havingValue = "openai")
public class OpenAiChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatClient.class);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper;

    public OpenAiChatClient(@Value("${pronto.openai.api-key}") String apiKey,
                             @Value("${pronto.openai.model}") String model,
                             @Value("${pronto.openai.timeout-ms}") long timeoutMs,
                             ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeoutMs);
        requestFactory.setReadTimeout((int) timeoutMs);

        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Sends one structured-output request and returns the model's parsed JSON payload.
     *
     * @param schemaName schema identifier reported to OpenAI (and used in logs)
     * @param schema     the JSON Schema, enforced with {@code strict: true}
     * @throws ApiException {@code AI_SERVICE_ERROR} after {@value #MAX_ATTEMPTS} failed attempts
     */
    public JsonNode requestStructured(String systemPrompt, String evidencePrompt, List<ImageAttachment> images,
                                       String schemaName, Map<String, Object> schema) {

        Map<String, Object> requestBody = buildRequestBody(systemPrompt, evidencePrompt, images, schemaName, schema);
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String rawResponse = restClient.post()
                        .uri(CHAT_COMPLETIONS_PATH)
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);

                return extractPayload(rawResponse);
            } catch (Exception e) {
                lastError = e;
                log.warn("openai.request.failed schema={} attempt={}/{} reason={}",
                        schemaName, attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }

        log.error("openai.request.exhausted schema={} attempts={}", schemaName, MAX_ATTEMPTS, lastError);
        throw new ApiException(ErrorCode.AI_SERVICE_ERROR,
                "OpenAI request failed after " + MAX_ATTEMPTS + " attempt(s).");
    }

    Map<String, Object> buildRequestBody(String systemPrompt, String evidencePrompt, List<ImageAttachment> images,
                                          String schemaName, Map<String, Object> schema) {

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", buildUserContent(evidencePrompt, images)));

        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", schemaName);
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", schema);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("response_format", Map.of("type", "json_schema", "json_schema", jsonSchema));
        return body;
    }

    /**
     * Images ride inline as data URLs — a presigned/local storage URL is not reachable from
     * OpenAI (see {@code dto.ImageAttachment}). The encoding already happened once, when the
     * attachment was resolved, so this method only assembles; it never re-encodes, however
     * many times the same attachment is sent. An attachment with no content is skipped rather
     * than sent as an empty data URL, which the API rejects.
     */
    private List<Map<String, Object>> buildUserContent(String evidencePrompt, List<ImageAttachment> images) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", evidencePrompt));

        if (images != null) {
            for (ImageAttachment image : images) {
                if (image == null || !image.hasContent()) {
                    log.warn("openai.image.skipped reason=empty key={}", image == null ? "null" : image.key());
                    continue;
                }
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", image.dataUri())));
            }
        }
        return content;
    }

    /** Unwraps the chat-completion envelope and parses the JSON string the model produced. */
    JsonNode extractPayload(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode messageContent = root.path("choices").path(0).path("message").path("content");

        if (!messageContent.isTextual() || messageContent.asText().isBlank()) {
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR, "OpenAI response did not contain a message body.");
        }
        return objectMapper.readTree(messageContent.asText());
    }
}
