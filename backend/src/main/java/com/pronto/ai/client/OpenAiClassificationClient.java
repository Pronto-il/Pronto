package com.pronto.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Category;
import com.pronto.professionals.repository.CategoryRepository;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Real OpenAI implementation ({@code pronto.ai.mode=openai}), per
 * {@code docs/architecture/api-contract-issues.md} §3.1. Not live-integration-tested this
 * milestone (no OpenAI key available per the task brief) — compiles and is wired correctly
 * behind {@link AiClassificationClient}, ready to activate by setting
 * {@code pronto.ai.mode=openai} plus a real {@code pronto.openai.api-key}.
 *
 * <p>Sends image bytes base64-encoded inline in the chat/vision request — never a URL — per
 * §3.1's "image reachability" decision. Requests a strict-JSON response
 * ({@code response_format: {type: "json_object"}}) so the reply can be parsed without a
 * free-text-extraction heuristic. Retries once on any failure (timeout, non-2xx, malformed
 * JSON) before surfacing {@code 502 AI_SERVICE_ERROR} — a simple, fixed retry policy; §2.1
 * step 6 requires "the configured retry policy" without specifying its shape.
 */
@Component
@ConditionalOnProperty(prefix = "pronto.ai", name = "mode", havingValue = "openai")
public class OpenAiClassificationClient implements AiClassificationClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClassificationClient.class);
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient restClient;
    private final String model;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;

    public OpenAiClassificationClient(
            @Value("${pronto.openai.api-key}") String apiKey,
            @Value("${pronto.openai.model}") String model,
            @Value("${pronto.openai.timeout-ms}") long timeoutMs,
            CategoryRepository categoryRepository,
            ObjectMapper objectMapper) {
        this.model = model;
        this.categoryRepository = categoryRepository;
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

    @Override
    public ClassificationResult classify(String description, List<ImageAttachment> images) {
        Map<String, Object> requestBody = buildRequestBody(description, images);

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String rawResponse = restClient.post()
                        .uri(CHAT_COMPLETIONS_PATH)
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);
                return parseResponse(rawResponse);
            } catch (Exception e) {
                lastError = e;
                log.warn("OpenAI classification attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        log.error("OpenAI classification failed after {} attempt(s).", MAX_ATTEMPTS, lastError);
        throw new ApiException(ErrorCode.AI_SERVICE_ERROR,
                "OpenAI classification request failed after " + MAX_ATTEMPTS + " attempt(s).");
    }

    private Map<String, Object> buildRequestBody(String description, List<ImageAttachment> images) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", description == null ? "" : description));
        for (ImageAttachment image : images) {
            String dataUri = "data:" + image.contentType() + ";base64,"
                    + Base64.getEncoder().encodeToString(image.content());
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUri)));
        }

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", buildSystemPrompt()),
                Map.of("role", "user", "content", content));

        return Map.of(
                "model", model,
                "response_format", Map.of("type", "json_object"),
                "messages", messages);
    }

    private String buildSystemPrompt() {
        String categoryList = categoryRepository.findAll().stream()
                .sorted(Comparator.comparingInt(Category::getDisplayOrder))
                .map(c -> c.getCode() + " (" + c.getNameHe() + ")")
                .collect(Collectors.joining(", "));

        return "את/ה מסווג/ת תקלות בית עבור פלטפורמת שירותי בית בשם Pronto. "
                + "בהינתן תיאור תקלה בעברית (ולעיתים גם תמונות), עליך לבחור את קטגוריית השירות "
                + "המתאימה ביותר מתוך הרשימה הבאה בלבד: " + categoryList + ". "
                + "השב/י אך ורק ב-JSON תקין, ללא טקסט נוסף לפני או אחרי, בפורמט המדויק הבא: "
                + "{\"categoryCode\": \"<אחד מקודי הקטגוריה לעיל בדיוק>\", "
                + "\"confidence\": <מספר בין 0 ל-1 המבטא את מידת הביטחון שלך>, "
                + "\"explanation\": \"<הסבר קצר בעברית מדוע נבחרה קטגוריה זו>\"}.";
    }

    private ClassificationResult parseResponse(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode messageContent = root.path("choices").path(0).path("message").path("content");
        if (!messageContent.isTextual()) {
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR, "OpenAI response did not contain a message body.");
        }

        JsonNode parsed = objectMapper.readTree(messageContent.asText());
        String categoryCode = parsed.path("categoryCode").isMissingNode() ? null : parsed.get("categoryCode").asText();
        Double confidence = parsed.has("confidence") && !parsed.get("confidence").isNull()
                ? parsed.get("confidence").asDouble() : null;
        String explanation = parsed.path("explanation").isMissingNode() ? null : parsed.get("explanation").asText();

        if (categoryCode == null || categoryCode.isBlank() || explanation == null) {
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR, "OpenAI response JSON was missing required fields.");
        }
        return new ClassificationResult(categoryCode, confidence, explanation);
    }
}
