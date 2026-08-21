package com.pronto.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.pronto.ai.dto.LikelyIssue;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the model's structured payload into a {@code ProfessionalBriefResponse}.
 *
 * <p>Tuned to be more forgiving than {@code ClassificationResponseParser}, because the
 * consequences differ: a bad routing decision sends the wrong professional, whereas a brief
 * missing its parts list is simply a thinner brief. So only a completely unusable payload —
 * not an object, or with no problem summary — is a hard failure; every optional list
 * degrades to empty, blank entries are dropped, and a hypothesis with no supporting evidence
 * is kept but logged (it is the one thing the prompt explicitly forbids inventing).
 */
public final class ProfessionalBriefParser {

    private static final Logger log = LoggerFactory.getLogger(ProfessionalBriefParser.class);

    private ProfessionalBriefParser() {
    }

    public static ProfessionalBriefResponse parse(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw malformed("brief payload was not a JSON object");
        }

        String customerProblemSummary = optionalText(payload, "customerProblemSummary");
        if (customerProblemSummary == null) {
            throw malformed("customerProblemSummary was missing or blank");
        }

        return new ProfessionalBriefResponse(
                customerProblemSummary,
                optionalText(payload, "clarificationSummary"),
                textArray(payload.path("imageObservations")),
                parseLikelyIssue(payload.path("likelyIssue")),
                textArray(payload.path("possibleCauses")),
                textArray(payload.path("recommendedTools")),
                textArray(payload.path("recommendedParts")),
                textArray(payload.path("safetyNotes")));
    }

    private static LikelyIssue parseLikelyIssue(JsonNode node) {
        if (!node.isObject()) {
            log.warn("ai.brief.parse likelyIssue=absent — the brief will be persisted without a hypothesis.");
            return null;
        }

        String description = optionalText(node, "description");
        if (description == null) {
            log.warn("ai.brief.parse likelyIssue.description=blank — dropping the hypothesis.");
            return null;
        }

        JsonNode confidenceNode = node.path("confidence");
        double confidence = confidenceNode.isNumber() ? confidenceNode.asDouble() : 0;
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
            log.warn("ai.brief.parse likelyIssue.confidence=out-of-range value={} — clamping.", confidence);
            confidence = Math.max(0, Math.min(1, Double.isNaN(confidence) ? 0 : confidence));
        }

        List<String> evidence = textArray(node.path("evidence"));
        if (evidence.isEmpty()) {
            log.warn("ai.brief.parse likelyIssue.evidence=0 — hypothesis kept but unsupported.");
        }

        return new LikelyIssue(description, confidence, evidence);
    }

    private static List<String> textArray(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(entry -> {
            if (entry.isTextual() && !entry.asText().isBlank()) {
                values.add(entry.asText().trim());
            }
        });
        return List.copyOf(values);
    }

    private static String optionalText(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private static ApiException malformed(String detail) {
        log.warn("ai.brief.invalid detail={}", detail);
        return new ApiException(ErrorCode.AI_SERVICE_ERROR, "OpenAI returned an unusable professional brief.");
    }
}
