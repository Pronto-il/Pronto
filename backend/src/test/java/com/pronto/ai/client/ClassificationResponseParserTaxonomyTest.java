package com.pronto.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.ai.taxonomy.Intent;
import com.pronto.ai.taxonomy.Urgency;
import com.pronto.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing of the four classification fields added in {@code classification-v6}.
 *
 * <p>Every one of them is <b>soft</b>: unreadable values are logged and dropped, never thrown on.
 * The reasoning is the same as for the existing soft cases — the routing decision, which is what
 * a customer is actually waiting for, does not depend on any of these, and failing a whole
 * classification because the model wrote {@code "URGENT"} instead of {@code "HIGH"} would trade a
 * working answer for no answer. The hard failures are unchanged and still hard.
 */
class ClassificationResponseParserTaxonomyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aCompleteResponseIsParsedIntoTheFullShape() {
        ClassificationResponse response = ClassificationResponseParser.parse(json("""
                {
                  "detectedProfession": "אינסטלטור",
                  "professionCode": "PLUMBER",
                  "subcategoryCode": "BURST_PIPE_OR_MAJOR_LEAK",
                  "intent": "EMERGENCY",
                  "urgency": "CRITICAL",
                  "primaryCategoryCode": "plumbing",
                  "confidence": 0.96,
                  "needsClarification": false,
                  "ambiguityReason": null,
                  "candidates": [{"categoryCode": "plumbing", "confidence": 0.96}],
                  "nextQuestion": null
                }"""));

        assertThat(response.detectedProfession()).isEqualTo("אינסטלטור");
        assertThat(response.professionCode()).isEqualTo("PLUMBER");
        assertThat(response.subcategoryCode()).isEqualTo("BURST_PIPE_OR_MAJOR_LEAK");
        assertThat(response.intent()).isEqualTo(Intent.EMERGENCY);
        assertThat(response.urgency()).isEqualTo(Urgency.CRITICAL);
        assertThat(response.primaryCategoryCode()).isEqualTo("plumbing");
    }

    /**
     * The unsupported-profession shape: a full classification and deliberately no dispatch.
     * Parsing must not treat the null category or the empty candidate list as a defect — this is
     * a successful response, and the policy reads that emptiness as the answer.
     */
    @Test
    void anUndispatchableTradeParsesAsACompleteClassificationWithNoCategory() {
        ClassificationResponse response = ClassificationResponseParser.parse(json("""
                {
                  "detectedProfession": "טכנאי גז",
                  "professionCode": "GAS_TECHNICIAN",
                  "subcategoryCode": "SUSPECTED_GAS_LEAK",
                  "intent": "EMERGENCY",
                  "urgency": "CRITICAL",
                  "primaryCategoryCode": null,
                  "confidence": 0.95,
                  "needsClarification": false,
                  "ambiguityReason": null,
                  "candidates": [],
                  "nextQuestion": null
                }"""));

        assertThat(response.professionCode()).isEqualTo("GAS_TECHNICIAN");
        assertThat(response.primaryCategoryCode()).isNull();
        assertThat(response.candidates()).isEmpty();
        assertThat(response.confidence())
                .as("confidence is about the TRADE, and stays high even with nothing to dispatch")
                .isEqualTo(0.95);
    }

    @Test
    void unrecognisedIntentAndUrgencyAreDroppedRatherThanThrownOn() {
        ClassificationResponse response = ClassificationResponseParser.parse(json("""
                {
                  "detectedProfession": "אינסטלטור",
                  "professionCode": "PLUMBER",
                  "subcategoryCode": "CLOGGED_DRAIN",
                  "intent": "URGENT",
                  "urgency": "VERY_HIGH",
                  "primaryCategoryCode": "plumbing",
                  "confidence": 0.9,
                  "needsClarification": false,
                  "ambiguityReason": null,
                  "candidates": [{"categoryCode": "plumbing", "confidence": 0.9}],
                  "nextQuestion": null
                }"""));

        assertThat(response.intent()).isNull();
        assertThat(response.urgency()).isNull();
        // The routing half is untouched, which is the entire point of these being soft.
        assertThat(response.primaryCategoryCode()).isEqualTo("plumbing");
        assertThat(response.professionCode()).isEqualTo("PLUMBER");
    }

    @Test
    void theFourNewFieldsAreOptionalSoAnOlderResponseShapeStillParses() {
        ClassificationResponse response = ClassificationResponseParser.parse(json("""
                {
                  "detectedProfession": "אינסטלטור",
                  "primaryCategoryCode": "plumbing",
                  "confidence": 0.9,
                  "needsClarification": false,
                  "ambiguityReason": null,
                  "candidates": [{"categoryCode": "plumbing", "confidence": 0.9}],
                  "nextQuestion": null
                }"""));

        assertThat(response.professionCode()).isNull();
        assertThat(response.subcategoryCode()).isNull();
        assertThat(response.intent()).isNull();
        assertThat(response.urgency()).isNull();
        assertThat(response.primaryCategoryCode()).isEqualTo("plumbing");
    }

    @Test
    void intentAndUrgencyParsingIsCaseInsensitiveAndTolerantOfWhitespace() {
        assertThat(Intent.parse(" repair ")).contains(Intent.REPAIR);
        assertThat(Urgency.parse("critical")).contains(Urgency.CRITICAL);
        assertThat(Intent.parse("nonsense")).isEmpty();
        assertThat(Urgency.parse(null)).isEmpty();
    }

    /**
     * The hard failures are unchanged. Adding four soft fields must not have softened the
     * conditions that genuinely make a response unusable — the fallback behaviour the whole
     * pipeline depends on is built on these still throwing.
     */
    @Test
    void malformedResponsesStillFailHard() {
        assertThatThrownBy(() -> ClassificationResponseParser.parse(json("""
                {"professionCode": "PLUMBER", "needsClarification": false, "candidates": []}""")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("unusable");

        assertThatThrownBy(() -> ClassificationResponseParser.parse(json("""
                {"confidence": 1.7, "needsClarification": false, "candidates": []}""")))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> ClassificationResponseParser.parse(json("""
                {"confidence": 0.9, "candidates": []}""")))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> ClassificationResponseParser.parse(json("\"not an object\"")))
                .isInstanceOf(ApiException.class);
    }
}
