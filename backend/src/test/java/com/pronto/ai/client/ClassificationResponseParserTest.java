package com.pronto.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Response validation, against hand-built payloads — this is where "never trust the model's
 * output" is actually implemented.
 *
 * <p>The tests are split along the parser's deliberate severity boundary: payloads that cannot
 * be reasoned about at all must fail loudly (and get retried, then surface a clean
 * {@code AI_SERVICE_ERROR}), while merely inconsistent ones must pass through so the routing
 * policy — which has a correct, safe answer for them — gets to decide. Failing the customer's
 * request over a recoverable inconsistency would be the worse bug.
 */
class ClassificationResponseParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode payload(String json) throws Exception {
        return mapper.readTree(json);
    }

    private ClassificationResponse parse(String json) throws Exception {
        return ClassificationResponseParser.parse(payload(json));
    }

    // -- valid ------------------------------------------------------------------------------

    @Test
    void parsesACommittedClassification() throws Exception {
        ClassificationResponse response = parse("""
                {
                  "primaryCategoryCode": "plumbing",
                  "confidence": 0.91,
                  "needsClarification": false,
                  "ambiguityReason": null,
                  "candidates": [
                    {"categoryCode": "plumbing", "confidence": 0.91},
                    {"categoryCode": "ac_hvac", "confidence": 0.06}
                  ],
                  "nextQuestion": null
                }
                """);

        assertThat(response.primaryCategoryCode()).isEqualTo("plumbing");
        assertThat(response.confidence()).isEqualTo(0.91);
        assertThat(response.needsClarification()).isFalse();
        assertThat(response.candidates())
                .containsExactly(new CategoryCandidate("plumbing", 0.91), new CategoryCandidate("ac_hvac", 0.06));
        assertThat(response.nextQuestion()).isNull();
    }

    @Test
    void parsesAClarificationQuestionIncludingItsInternalDistinguishesBetween() throws Exception {
        ClassificationResponse response = parse("""
                {
                  "primaryCategoryCode": null,
                  "confidence": 0.45,
                  "needsClarification": true,
                  "ambiguityReason": "Leak source unclear.",
                  "candidates": [
                    {"categoryCode": "plumbing", "confidence": 0.45},
                    {"categoryCode": "ac_hvac", "confidence": 0.40}
                  ],
                  "nextQuestion": {
                    "id": "q1",
                    "question": "מאיפה מגיעים המים?",
                    "options": ["מהמזגן", "מצינור", "אני לא בטוח/ה"],
                    "distinguishesBetween": ["plumbing", "ac_hvac"]
                  }
                }
                """);

        assertThat(response.primaryCategoryCode()).isNull();
        assertThat(response.ambiguityReason()).isEqualTo("Leak source unclear.");
        assertThat(response.nextQuestion().question()).isEqualTo("מאיפה מגיעים המים?");
        assertThat(response.nextQuestion().options()).containsExactly("מהמזגן", "מצינור", "אני לא בטוח/ה");
        assertThat(response.nextQuestion().distinguishesBetween()).containsExactly("plumbing", "ac_hvac");
    }

    @Test
    void blankOptionEntriesAreDropped() throws Exception {
        ClassificationResponse response = parse("""
                {
                  "primaryCategoryCode": null, "confidence": 0.4, "needsClarification": true,
                  "ambiguityReason": "x", "candidates": [],
                  "nextQuestion": {"id": "q", "question": "שאלה", "options": ["א", "  ", "ב"],
                                    "distinguishesBetween": []}
                }
                """);

        assertThat(response.nextQuestion().options()).containsExactly("א", "ב");
    }

    // -- soft problems: pass through, the policy handles them ---------------------------------

    @Test
    void needsClarificationWithNoQuestionIsPassedThroughForThePolicyToResolve() throws Exception {
        ClassificationResponse response = parse("""
                {
                  "primaryCategoryCode": "plumbing", "confidence": 0.5, "needsClarification": true,
                  "ambiguityReason": "unclear", "candidates": [{"categoryCode": "plumbing", "confidence": 0.5}],
                  "nextQuestion": null
                }
                """);

        assertThat(response.needsClarification()).isTrue();
        assertThat(response.nextQuestion()).isNull();
    }

    @Test
    void anEmptyCandidateListIsPassedThroughRatherThanFailingTheRequest() throws Exception {
        ClassificationResponse response = parse("""
                {
                  "primaryCategoryCode": null, "confidence": 0.1, "needsClarification": false,
                  "ambiguityReason": null, "candidates": [], "nextQuestion": null
                }
                """);

        assertThat(response.candidates()).isEmpty();
    }

    // -- hard failures -----------------------------------------------------------------------

    @Test
    void aMissingConfidenceIsRejected() {
        assertThatThrownBy(() -> parse("""
                {"primaryCategoryCode": "plumbing", "needsClarification": false,
                 "ambiguityReason": null, "candidates": [], "nextQuestion": null}
                """))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(ErrorCode.AI_SERVICE_ERROR);
    }

    @Test
    void aConfidenceOutsideZeroToOneIsRejected() {
        assertThatThrownBy(() -> parse("""
                {"primaryCategoryCode": "plumbing", "confidence": 1.4, "needsClarification": false,
                 "ambiguityReason": null, "candidates": [], "nextQuestion": null}
                """)).isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> parse("""
                {"primaryCategoryCode": "plumbing", "confidence": -0.2, "needsClarification": false,
                 "ambiguityReason": null, "candidates": [], "nextQuestion": null}
                """)).isInstanceOf(ApiException.class);
    }

    @Test
    void aMissingNeedsClarificationFlagIsRejected() {
        assertThatThrownBy(() -> parse("""
                {"primaryCategoryCode": "plumbing", "confidence": 0.9,
                 "ambiguityReason": null, "candidates": [], "nextQuestion": null}
                """)).isInstanceOf(ApiException.class);
    }

    @Test
    void aCandidateWithoutACategoryCodeIsRejected() {
        assertThatThrownBy(() -> parse("""
                {"primaryCategoryCode": "plumbing", "confidence": 0.9, "needsClarification": false,
                 "ambiguityReason": null, "candidates": [{"confidence": 0.9}], "nextQuestion": null}
                """)).isInstanceOf(ApiException.class);
    }

    @Test
    void aCandidateWithAnOutOfRangeConfidenceIsRejected() {
        assertThatThrownBy(() -> parse("""
                {"primaryCategoryCode": "plumbing", "confidence": 0.9, "needsClarification": false,
                 "ambiguityReason": null,
                 "candidates": [{"categoryCode": "plumbing", "confidence": 12}], "nextQuestion": null}
                """)).isInstanceOf(ApiException.class);
    }

    @Test
    void aQuestionObjectWithNoQuestionTextIsRejected() {
        assertThatThrownBy(() -> parse("""
                {"primaryCategoryCode": null, "confidence": 0.4, "needsClarification": true,
                 "ambiguityReason": "x", "candidates": [],
                 "nextQuestion": {"id": "q", "options": ["א", "ב"], "distinguishesBetween": []}}
                """)).isInstanceOf(ApiException.class);
    }

    @Test
    void aNonObjectPayloadIsRejected() throws Exception {
        JsonNode notAnObject = payload("[1, 2, 3]");
        assertThatThrownBy(() -> ClassificationResponseParser.parse(notAnObject))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ClassificationResponseParser.parse(null))
                .isInstanceOf(ApiException.class);
    }
}
