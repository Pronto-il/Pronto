package com.pronto.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Category;
import com.pronto.professionals.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Exercises the structured-output response parsing/validation that backs the
 * CLASSIFIED/QUESTIONS clarification-question extension (§2.1 of
 * {@code docs/architecture/api-contract-issues.md}), directly against hand-built raw OpenAI
 * chat-completion JSON bodies — package-private {@code parseResponse} is called straight, so
 * no real HTTP call/mock server is needed. This is where the "malformed AI response"
 * validation and the CLASSIFIED/QUESTIONS invariants (max 3 questions, categoryCode
 * null-iff-QUESTIONS, etc.) actually live.
 */
class OpenAiClassificationClientTest {

    private OpenAiClassificationClient client;

    @BeforeEach
    void setUp() {
        CategoryRepository categoryRepository = Mockito.mock(CategoryRepository.class);
        Category plumbing = Mockito.mock(Category.class);
        when(plumbing.getCode()).thenReturn("plumbing");
        when(categoryRepository.findAll()).thenReturn(List.of(plumbing));

        client = new OpenAiClassificationClient("test-api-key", "gpt-4o-mini", 5000,
                categoryRepository, new ObjectMapper());
    }

    @Test
    void parseResponse_clearClassifiedResult_isParsedSuccessfully() throws Exception {
        String raw = chatCompletion("""
                {
                  "status": "CLASSIFIED",
                  "categoryCode": "plumbing",
                  "confidence": 0.96,
                  "explanation": "Leaking pipe under the sink.",
                  "questions": []
                }
                """);

        ClassificationResult result = client.parseResponse(raw);

        assertThat(result.status()).isEqualTo(ClassificationStatus.CLASSIFIED);
        assertThat(result.categoryCode()).isEqualTo("plumbing");
        assertThat(result.confidence()).isEqualTo(0.96);
        assertThat(result.questions()).isEmpty();
    }

    @Test
    void parseResponse_contradictionBetweenTextAndImage_returnsQuestions() throws Exception {
        String raw = chatCompletion("""
                {
                  "status": "QUESTIONS",
                  "categoryCode": null,
                  "confidence": 0.61,
                  "explanation": "Description suggests AC, image may show a plumbing leak.",
                  "questions": [
                    {
                      "id": "q1",
                      "question": "Where does the water appear to be coming from?",
                      "options": ["Directly from the air conditioner", "From a pipe or wall near it", "I am not sure"]
                    }
                  ]
                }
                """);

        ClassificationResult result = client.parseResponse(raw);

        assertThat(result.status()).isEqualTo(ClassificationStatus.QUESTIONS);
        assertThat(result.categoryCode()).isNull();
        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0).options()).contains("I am not sure");
    }

    @Test
    void parseResponse_ambiguousEvidence_allowsUpToThreeQuestions() throws Exception {
        String raw = chatCompletion("""
                {
                  "status": "QUESTIONS",
                  "categoryCode": null,
                  "confidence": 0.5,
                  "explanation": "Two categories are realistically possible.",
                  "questions": [
                    {"id": "q1", "question": "Question 1?", "options": ["A", "B"]},
                    {"id": "q2", "question": "Question 2?", "options": ["A", "B"]},
                    {"id": "q3", "question": "Question 3?", "options": ["A", "B"]}
                  ]
                }
                """);

        ClassificationResult result = client.parseResponse(raw);

        assertThat(result.questions()).hasSize(3);
    }

    @Test
    void parseResponse_questionsResponse_neverExceedsThreeQuestions_rejectsFour() {
        String raw = chatCompletion("""
                {
                  "status": "QUESTIONS",
                  "categoryCode": null,
                  "confidence": 0.5,
                  "explanation": "Too many questions.",
                  "questions": [
                    {"id": "q1", "question": "Question 1?", "options": ["A", "B"]},
                    {"id": "q2", "question": "Question 2?", "options": ["A", "B"]},
                    {"id": "q3", "question": "Question 3?", "options": ["A", "B"]},
                    {"id": "q4", "question": "Question 4?", "options": ["A", "B"]}
                  ]
                }
                """);

        assertThatThrownBy(() -> client.parseResponse(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.AI_SERVICE_ERROR));
    }

    @Test
    void parseResponse_questionsResponse_rejectsZeroQuestions() {
        String raw = chatCompletion("""
                {
                  "status": "QUESTIONS",
                  "categoryCode": null,
                  "confidence": 0.5,
                  "explanation": "No questions provided.",
                  "questions": []
                }
                """);

        assertThatThrownBy(() -> client.parseResponse(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.AI_SERVICE_ERROR));
    }

    @Test
    void parseResponse_classifiedWithQuestions_isRejectedAsMalformed() {
        String raw = chatCompletion("""
                {
                  "status": "CLASSIFIED",
                  "categoryCode": "plumbing",
                  "confidence": 0.9,
                  "explanation": "explanation",
                  "questions": [
                    {"id": "q1", "question": "Question 1?", "options": ["A", "B"]}
                  ]
                }
                """);

        assertThatThrownBy(() -> client.parseResponse(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.AI_SERVICE_ERROR));
    }

    @Test
    void parseResponse_questionsWithACategoryCode_isRejectedAsMalformed() {
        String raw = chatCompletion("""
                {
                  "status": "QUESTIONS",
                  "categoryCode": "plumbing",
                  "confidence": 0.6,
                  "explanation": "explanation",
                  "questions": [
                    {"id": "q1", "question": "Question 1?", "options": ["A", "B"]}
                  ]
                }
                """);

        assertThatThrownBy(() -> client.parseResponse(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.AI_SERVICE_ERROR));
    }

    @Test
    void parseResponse_classifiedWithoutCategoryCode_isRejectedAsMalformed() {
        String raw = chatCompletion("""
                {
                  "status": "CLASSIFIED",
                  "categoryCode": null,
                  "confidence": 0.9,
                  "explanation": "explanation",
                  "questions": []
                }
                """);

        assertThatThrownBy(() -> client.parseResponse(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.AI_SERVICE_ERROR));
    }

    @Test
    void parseResponse_confidenceAboveOne_isRejected() {
        String raw = chatCompletion("""
                {
                  "status": "CLASSIFIED",
                  "categoryCode": "plumbing",
                  "confidence": 1.4,
                  "explanation": "explanation",
                  "questions": []
                }
                """);

        assertThatThrownBy(() -> client.parseResponse(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.AI_SERVICE_ERROR));
    }

    @Test
    void parseResponse_confidenceBelowZero_isRejected() {
        String raw = chatCompletion("""
                {
                  "status": "CLASSIFIED",
                  "categoryCode": "plumbing",
                  "confidence": -0.1,
                  "explanation": "explanation",
                  "questions": []
                }
                """);

        assertThatThrownBy(() -> client.parseResponse(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.AI_SERVICE_ERROR));
    }

    @Test
    void parseResponse_missingStatus_isRejected() {
        String raw = chatCompletion("""
                {
                  "categoryCode": "plumbing",
                  "confidence": 0.9,
                  "explanation": "explanation",
                  "questions": []
                }
                """);

        assertThatThrownBy(() -> client.parseResponse(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.AI_SERVICE_ERROR));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildRequestBody_categoryCodeEnumIsGeneratedDynamicallyFromDatabaseCategories() {
        Map<String, Object> requestBody = client.buildRequestBody("description", List.of(), null);

        Map<String, Object> responseFormat = (Map<String, Object>) requestBody.get("response_format");
        Map<String, Object> jsonSchema = (Map<String, Object>) responseFormat.get("json_schema");
        Map<String, Object> schema = (Map<String, Object>) jsonSchema.get("schema");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> categoryCode = (Map<String, Object>) properties.get("categoryCode");
        List<Object> enumValues = (List<Object>) categoryCode.get("enum");

        // Exactly the (mocked) database's category codes, plus null for the QUESTIONS case —
        // never a hard-coded list.
        assertThat(enumValues).containsExactlyInAnyOrder("plumbing", null);
    }

    @Test
    void buildRequestBody_emptyCategoryDatabase_failsSafelyAndExplicitly() {
        CategoryRepository emptyCategoryRepository = Mockito.mock(CategoryRepository.class);
        when(emptyCategoryRepository.findAll()).thenReturn(List.of());
        OpenAiClassificationClient clientWithNoCategories = new OpenAiClassificationClient(
                "test-api-key", "gpt-4o-mini", 5000, emptyCategoryRepository, new ObjectMapper());

        assertThatThrownBy(() -> clientWithNoCategories.buildRequestBody("description", List.of(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.AI_SERVICE_ERROR));
    }

    private String chatCompletion(String structuredContentJson) {
        String escapedContent = structuredContentJson
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{\"choices\":[{\"message\":{\"content\":\"" + escapedContent + "\"}}]}";
    }
}
