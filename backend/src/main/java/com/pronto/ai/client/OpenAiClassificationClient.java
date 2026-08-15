package com.pronto.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationStatus;
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
 * Real OpenAI implementation ({@code pronto.ai.mode=openai}).
 *
 * Sends the customer's issue description and optional images to OpenAI in order to
 * classify the request into the most appropriate service category. When the description and
 * the attached images meaningfully disagree — or more than one category is realistically
 * possible — OpenAI is asked to return up to 3 clarification questions instead of guessing
 * ({@link #classify}). Once the customer answers, exactly one further request
 * ({@link #classifyWithClarification}) produces a final, non-{@code QUESTIONS} result.
 *
 * Images are sent inline as Base64 data URLs.
 *
 * Uses Structured Outputs with a strict JSON Schema so that:
 * - status is either CLASSIFIED or QUESTIONS.
 * - categoryCode must match one of the category codes configured in the database, or be
 *   null when status is QUESTIONS.
 * - confidence must be between 0 and 1.
 * - explanation must be returned as a string.
 * - questions is an array of at most 3 {id, question, options} objects.
 *
 * Retries once on failure before returning {@code AI_SERVICE_ERROR}. The parsed response is
 * additionally validated against the CLASSIFIED/QUESTIONS invariants the schema's
 * {@code strict} mode can't fully express on its own (e.g. "QUESTIONS needs 1-3 questions,
 * not 0 and not more than 3") — a malformed/rule-violating response is treated the same as
 * a transport failure and retried, then surfaces {@code AI_SERVICE_ERROR}.
 */
@Component
@ConditionalOnProperty(prefix = "pronto.ai", name = "mode", havingValue = "openai")
public class OpenAiClassificationClient implements AiClassificationClient {

    private static final Logger log =
            LoggerFactory.getLogger(OpenAiClassificationClient.class);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_QUESTIONS = 3;

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

        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout((int) timeoutMs);
        requestFactory.setReadTimeout((int) timeoutMs);

        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .requestFactory(requestFactory)
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + apiKey
                )
                .defaultHeader(
                        HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE
                )
                .build();
    }

    @Override
    public ClassificationResult classify(
            String description,
            List<ImageAttachment> images
    ) {
        Map<String, Object> requestBody = buildRequestBody(description, images, null);
        return performClassification(requestBody);
    }

    @Override
    public ClassificationResult classifyWithClarification(
            String description,
            List<ImageAttachment> images,
            List<ClarificationAnswer> clarificationAnswers
    ) {
        Map<String, Object> requestBody = buildRequestBody(description, images, clarificationAnswers);
        ClassificationResult result = performClassification(requestBody);

        if (result.status() != ClassificationStatus.CLASSIFIED) {
            // A single round of clarification is a hard business rule (never re-ask) — an
            // OpenAI response that ignores the final-round system prompt and returns
            // QUESTIONS again is treated as a failure to produce the required result, not
            // silently forwarded to the caller.
            log.error("OpenAI returned {} on the clarification round; expected CLASSIFIED.", result.status());
            throw new ApiException(
                    ErrorCode.AI_SERVICE_ERROR,
                    "OpenAI failed to produce a final classification after clarification answers."
            );
        }

        return result;
    }

    private ClassificationResult performClassification(Map<String, Object> requestBody) {
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

                log.warn(
                        "OpenAI classification attempt {}/{} failed: {}",
                        attempt,
                        MAX_ATTEMPTS,
                        e.getMessage()
                );
            }
        }

        log.error(
                "OpenAI classification failed after {} attempt(s).",
                MAX_ATTEMPTS,
                lastError
        );

        throw new ApiException(
                ErrorCode.AI_SERVICE_ERROR,
                "OpenAI classification request failed after "
                        + MAX_ATTEMPTS
                        + " attempt(s)."
        );
    }

    Map<String, Object> buildRequestBody(
            String description,
            List<ImageAttachment> images,
            List<ClarificationAnswer> clarificationAnswers
    ) {

        List<Category> categories = categoryRepository.findAll().stream()
                .sorted(Comparator.comparingInt(Category::getDisplayOrder))
                .toList();

        if (categories.isEmpty()) {
            throw new ApiException(
                    ErrorCode.AI_SERVICE_ERROR,
                    "No service categories are configured."
            );
        }

        List<String> categoryCodes = categories.stream()
                .map(Category::getCode)
                .toList();

        boolean finalRound = clarificationAnswers != null && !clarificationAnswers.isEmpty();

        List<Map<String, Object>> messages = List.of(
                Map.of(
                        "role", "system",
                        "content", buildSystemPrompt(categories, finalRound)
                ),
                Map.of(
                        "role", "user",
                        "content", buildUserContent(description, images, clarificationAnswers)
                )
        );

        return Map.of(
                "model", model,

                "messages", messages,

                "response_format", Map.of(
                        "type", "json_schema",

                        "json_schema", Map.of(
                                "name", "service_classification",
                                "strict", true,
                                "schema", buildSchema(categoryCodes)
                        )
                )
        );
    }

    private List<Map<String, Object>> buildUserContent(
            String description,
            List<ImageAttachment> images,
            List<ClarificationAnswer> clarificationAnswers
    ) {
        List<Map<String, Object>> content = new ArrayList<>();

        content.add(Map.of(
                "type", "text",
                "text", "Customer issue description:\n"
                        + (
                        description == null || description.isBlank()
                                ? "No description was provided."
                                : description
                )
        ));

        if (images != null) {
            for (ImageAttachment image : images) {

                String dataUri = "data:"
                        + image.contentType()
                        + ";base64,"
                        + Base64.getEncoder()
                        .encodeToString(image.content());

                content.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of(
                                "url", dataUri
                        )
                ));
            }
        }

        if (clarificationAnswers != null && !clarificationAnswers.isEmpty()) {
            String qa = clarificationAnswers.stream()
                    .map(a -> "Question:\n" + a.question() + "\n\nAnswer:\n" + a.answer())
                    .collect(Collectors.joining("\n\n"));

            content.add(Map.of(
                    "type", "text",
                    "text", "Clarification questions already asked, and the customer's answers:\n\n" + qa
            ));
        }

        return content;
    }

    /**
     * {@code categoryCode} is modeled as nullable via a {@code ["string", "null"]} type union
     * plus a {@code null}-inclusive enum — OpenAI Structured Outputs' documented pattern for
     * an optional/nullable enum field under {@code strict: true} (every property must stay
     * listed in {@code required}, but its value may still be {@code null}).
     */
    private Map<String, Object> buildSchema(List<String> categoryCodes) {

        List<Object> categoryCodeEnum = new ArrayList<>(categoryCodes);
        categoryCodeEnum.add(null);

        Map<String, Object> questionSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of("type", "string"),
                        "question", Map.of("type", "string"),
                        "options", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                        )
                ),
                "required", List.of("id", "question", "options"),
                "additionalProperties", false
        );

        return Map.of(
                "type", "object",

                "properties", Map.of(
                        "status", Map.of(
                                "type", "string",
                                "enum", List.of("CLASSIFIED", "QUESTIONS")
                        ),

                        "categoryCode", Map.of(
                                "type", List.of("string", "null"),
                                "enum", categoryCodeEnum
                        ),

                        "confidence", Map.of(
                                "type", "number",
                                "minimum", 0,
                                "maximum", 1
                        ),

                        "explanation", Map.of(
                                "type", "string"
                        ),

                        "questions", Map.of(
                                "type", "array",
                                "items", questionSchema,
                                "maxItems", MAX_QUESTIONS
                        )
                ),

                "required", List.of(
                        "status",
                        "categoryCode",
                        "confidence",
                        "explanation",
                        "questions"
                ),

                "additionalProperties", false
        );
    }

    private String buildSystemPrompt(List<Category> categories, boolean finalRound) {

        String categoryList = categories.stream()
                .map(category -> "- " + category.getCode() + ": " + category.getNameHe())
                .collect(Collectors.joining("\n"));

        if (finalRound) {
            return """
                You classify home-service requests for Pronto.

                Available categories:
                %s

                The customer already answered clarification questions.

                Use:
                - original description
                - original images
                - clarification questions
                - customer answers

                Return a FINAL classification.

                Rules:
                - status must be "CLASSIFIED".
                - Never ask more questions.
                - categoryCode must exactly match one available category code.
                - questions must be [].
                - Choose the category that best matches the primary issue.
                - Use clarification answers to resolve previous text/image ambiguity.
                - confidence must honestly reflect certainty from 0 to 1.
                - explanation must be short and in English.
                """.formatted(categoryList);
        }

        return """
            You classify home-service requests for Pronto using the customer's description
            and attached images.

            Available categories:
            %s

            Return either "CLASSIFIED" or "QUESTIONS".

            CLASSIFIED:
            - Use when one category is sufficiently clear.
            - categoryCode must exactly match an available category code.
            - questions must be [].

            QUESTIONS:
            - Use only when text and images conflict, or multiple categories are realistically possible.
            - categoryCode must be null.
            - Ask only what is needed to choose between the competing categories.
            - Ask the minimum number of questions, maximum 3.
            - Prefer one question when it can resolve the ambiguity.
            - Do not diagnose one category in detail while ignoring the competing category.
            - If text and image show different problems, first ask which problem the customer wants fixed.
            - Every question must help choose between the competing categories.
            - Before asking a question, internally verify that its answer could affect the category choice.
            - Use simple closed-ended questions.
            - Options must be short ANSWERS, not questions.
            - Include "I am not sure" when useful.
            - Do not ask for information already clear from the text or images.
            - Do not ask generic follow-up questions.

            General:
            - Consider text and images together; neither automatically overrides the other.
            - Focus on the primary issue.
            - Never invent categories or category codes.
            - confidence must honestly reflect certainty from 0 to 1.
            - explanation must be short and in English.
            """.formatted(categoryList);
    }

    ClassificationResult parseResponse(
            String rawResponse
    ) throws Exception {

        JsonNode root = objectMapper.readTree(rawResponse);

        JsonNode messageContent = root
                .path("choices")
                .path(0)
                .path("message")
                .path("content");

        if (!messageContent.isTextual()) {
            throw new ApiException(
                    ErrorCode.AI_SERVICE_ERROR,
                    "OpenAI response did not contain a message body."
            );
        }

        JsonNode parsed =
                objectMapper.readTree(messageContent.asText());

        ClassificationStatus status = parseStatus(parsed);

        String categoryCode =
                parsed.hasNonNull("categoryCode")
                        ? parsed.get("categoryCode").asText()
                        : null;

        Double confidence =
                parsed.hasNonNull("confidence")
                        ? parsed.get("confidence").asDouble()
                        : null;

        String explanation =
                parsed.hasNonNull("explanation")
                        ? parsed.get("explanation").asText()
                        : null;

        List<ClarificationQuestion> questions = parseQuestions(parsed.path("questions"));

        if (confidence == null
                || explanation == null
                || explanation.isBlank()) {

            throw new ApiException(
                    ErrorCode.AI_SERVICE_ERROR,
                    "OpenAI response JSON was missing required fields."
            );
        }

        if (confidence < 0 || confidence > 1) {
            throw new ApiException(
                    ErrorCode.AI_SERVICE_ERROR,
                    "OpenAI returned an invalid confidence value."
            );
        }

        validateStatusInvariants(status, categoryCode, questions);

        return new ClassificationResult(status, categoryCode, confidence, explanation, questions);
    }

    private void validateStatusInvariants(ClassificationStatus status, String categoryCode,
                                           List<ClarificationQuestion> questions) {
        if (status == ClassificationStatus.CLASSIFIED) {
            if (categoryCode == null || categoryCode.isBlank()) {
                throw new ApiException(
                        ErrorCode.AI_SERVICE_ERROR,
                        "OpenAI returned CLASSIFIED without a categoryCode."
                );
            }
            if (!questions.isEmpty()) {
                throw new ApiException(
                        ErrorCode.AI_SERVICE_ERROR,
                        "OpenAI returned CLASSIFIED together with clarification questions."
                );
            }
        } else {
            if (categoryCode != null && !categoryCode.isBlank()) {
                throw new ApiException(
                        ErrorCode.AI_SERVICE_ERROR,
                        "OpenAI returned QUESTIONS together with a categoryCode."
                );
            }
            if (questions.isEmpty() || questions.size() > MAX_QUESTIONS) {
                throw new ApiException(
                        ErrorCode.AI_SERVICE_ERROR,
                        "OpenAI returned QUESTIONS with an invalid number of clarification questions."
                );
            }
        }
    }

    private ClassificationStatus parseStatus(JsonNode parsed) {
        String raw = parsed.hasNonNull("status") ? parsed.get("status").asText() : null;
        try {
            return ClassificationStatus.valueOf(raw);
        } catch (Exception e) {
            throw new ApiException(
                    ErrorCode.AI_SERVICE_ERROR,
                    "OpenAI returned an invalid or missing status value."
            );
        }
    }

    private List<ClarificationQuestion> parseQuestions(JsonNode questionsNode) {
        if (!questionsNode.isArray()) {
            return List.of();
        }

        List<ClarificationQuestion> questions = new ArrayList<>();
        for (JsonNode q : questionsNode) {
            String id = q.hasNonNull("id") ? q.get("id").asText() : null;
            String question = q.hasNonNull("question") ? q.get("question").asText() : null;

            List<String> options = new ArrayList<>();
            JsonNode optionsNode = q.path("options");
            if (optionsNode.isArray()) {
                optionsNode.forEach(o -> options.add(o.asText()));
            }

            if (id == null || id.isBlank() || question == null || question.isBlank() || options.isEmpty()) {
                throw new ApiException(
                        ErrorCode.AI_SERVICE_ERROR,
                        "OpenAI returned a malformed clarification question."
                );
            }

            questions.add(new ClarificationQuestion(id, question, options));
        }
        return questions;
    }
}
