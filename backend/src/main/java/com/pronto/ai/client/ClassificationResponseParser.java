package com.pronto.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.ai.taxonomy.Intent;
import com.pronto.ai.taxonomy.Urgency;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the model's structured payload into a {@code ClassificationResponse}, re-checking
 * everything the JSON Schema either cannot express or might not have been applied to.
 *
 * <p>Two severities, deliberately separated:
 * <ul>
 *   <li><b>Hard failures</b> throw {@code AI_SERVICE_ERROR} — a missing or non-numeric
 *       confidence, a confidence outside 0..1, a non-array candidate list, a candidate with
 *       no code, a malformed question object. These mean the response cannot be reasoned
 *       about at all, so the caller retries and then surfaces a clean error.</li>
 *   <li><b>Soft problems</b> are logged and passed through — for example
 *       {@code needsClarification: true} with no question, or an empty candidate list.
 *       {@code decision.RoutingDecisionPolicy} already has a correct, safe answer for both
 *       (commit rather than ask; use the controlled fallback), and failing the whole request
 *       would be a worse outcome for the customer than a slightly degraded decision.</li>
 * </ul>
 *
 * <p>Category codes are <i>not</i> validated here — that is the policy's job, since it is the
 * component that holds the live category list. Nothing downstream ever accepts an unknown code.
 * The same division applies to the classification layer: {@code professionCode} and
 * {@code subcategoryCode} are read as text here and checked against
 * {@code taxonomy.ProfessionTaxonomy} by {@code decision.RoutingDecisionPolicy}, which is the
 * component that holds it.
 */
public final class ClassificationResponseParser {

    private static final Logger log = LoggerFactory.getLogger(ClassificationResponseParser.class);

    private ClassificationResponseParser() {
    }

    public static ClassificationResponse parse(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw malformed("classification payload was not a JSON object");
        }

        // Soft, not hard. A missing profession label degrades the customer-facing message for an
        // unsupported trade; it does not make the response unreasonable-about, and failing the whole
        // classification over a label would be a worse outcome than the generic wording the policy
        // falls back to. Same severity split the rest of this parser uses.
        String detectedProfession = optionalText(payload, "detectedProfession");
        String professionCode = optionalText(payload, "professionCode");
        String subcategoryCode = optionalText(payload, "subcategoryCode");
        // Soft for the same reason the profession label is: an unreadable intent or urgency
        // degrades a piece of metadata, while the routing decision -- the thing a customer is
        // waiting on -- does not depend on either. Failing the whole classification because the
        // model wrote "URGENT" instead of "HIGH" would trade a working answer for none.
        Intent intent = parseEnum(payload, "intent", Intent::parse);
        Urgency urgency = parseEnum(payload, "urgency", Urgency::parse);
        String primaryCategoryCode = optionalText(payload, "primaryCategoryCode");
        double confidence = requiredConfidence(payload, "confidence");

        if (!payload.path("needsClarification").isBoolean()) {
            throw malformed("needsClarification was missing or not a boolean");
        }
        boolean needsClarification = payload.path("needsClarification").asBoolean();

        String ambiguityReason = optionalText(payload, "ambiguityReason");
        List<CategoryCandidate> candidates = parseCandidates(payload.path("candidates"));
        ClarificationQuestion nextQuestion = parseQuestion(payload.path("nextQuestion"));

        if (candidates.isEmpty()) {
            log.warn("ai.classification.parse candidates=0 primary={} — the routing policy will fall back.",
                    primaryCategoryCode);
        }
        if (needsClarification && nextQuestion == null) {
            log.warn("ai.classification.parse needsClarification=true question=absent — committing instead "
                    + "of asking.");
        }

        if (detectedProfession == null && primaryCategoryCode == null && candidates.isEmpty()) {
            log.warn("ai.classification.parse profession=absent primary=null candidates=0 — nothing to "
                    + "route on and nothing to name; the routing policy will fall back.");
        }

        return new ClassificationResponse(detectedProfession, professionCode, subcategoryCode, intent,
                urgency, primaryCategoryCode, confidence, needsClarification, ambiguityReason, candidates,
                nextQuestion);
    }

    /**
     * Reads a controlled-vocabulary field, logging and returning {@code null} for anything the
     * enum does not recognise.
     *
     * <p>The schema already constrains these to the enum's own names, so a miss here means the
     * schema was not applied — worth a log line, never worth failing the request over.
     */
    private static <T> T parseEnum(JsonNode payload, String fieldName,
                                    java.util.function.Function<String, java.util.Optional<T>> parser) {
        String raw = optionalText(payload, fieldName);
        if (raw == null) {
            return null;
        }
        java.util.Optional<T> parsed = parser.apply(raw);
        if (parsed.isEmpty()) {
            log.warn("ai.classification.parse {}={} is not a recognised value — dropping it.", fieldName, raw);
            return null;
        }
        return parsed.get();
    }

    private static List<CategoryCandidate> parseCandidates(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw malformed("candidates was not an array");
        }

        List<CategoryCandidate> candidates = new ArrayList<>();
        for (JsonNode entry : node) {
            String code = optionalText(entry, "categoryCode");
            if (code == null) {
                throw malformed("a candidate had no categoryCode");
            }
            candidates.add(new CategoryCandidate(code, requiredConfidence(entry, "confidence")));
        }
        return candidates;
    }

    /** {@code null} for an absent/null question; throws when a question object is present but unusable. */
    private static ClarificationQuestion parseQuestion(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw malformed("nextQuestion was neither an object nor null");
        }

        String question = optionalText(node, "question");
        if (question == null) {
            throw malformed("nextQuestion had no question text");
        }

        List<String> options = textArray(node.path("options"), "nextQuestion.options");
        List<String> distinguishesBetween = node.path("distinguishesBetween").isMissingNode()
                ? List.of()
                : textArray(node.path("distinguishesBetween"), "nextQuestion.distinguishesBetween");

        return new ClarificationQuestion(optionalText(node, "id"), question, options, distinguishesBetween);
    }

    private static List<String> textArray(JsonNode node, String fieldName) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw malformed(fieldName + " was not an array");
        }
        List<String> values = new ArrayList<>();
        node.forEach(entry -> {
            if (entry.isTextual() && !entry.asText().isBlank()) {
                values.add(entry.asText().trim());
            }
        });
        return List.copyOf(values);
    }

    private static double requiredConfidence(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.isNumber()) {
            throw malformed(fieldName + " was missing or not a number");
        }
        double confidence = value.asDouble();
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
            throw malformed(fieldName + " was outside the allowed 0..1 range");
        }
        return confidence;
    }

    private static String optionalText(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private static ApiException malformed(String detail) {
        log.warn("ai.classification.invalid detail={}", detail);
        return new ApiException(ErrorCode.AI_SERVICE_ERROR, "OpenAI returned an unusable classification response.");
    }
}
