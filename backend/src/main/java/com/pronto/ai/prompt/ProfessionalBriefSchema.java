package com.pronto.ai.prompt;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schema for {@code dto.ProfessionalBriefResponse} — the second, separate structured
 * response in this package. Same {@code strict: true} treatment and the same deliberate
 * omission of unsupported numeric/array-length keywords as {@link ClassificationSchema};
 * content rules ("empty array is a valid answer", "evidence must be traceable") are enforced
 * by the prompt and re-checked in {@code service.ProfessionalBriefService}.
 */
@Component
public class ProfessionalBriefSchema {

    public static final String SCHEMA_NAME = "pronto_professional_brief";

    public Map<String, Object> build() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("customerProblemSummary", Map.of("type", "string"));
        properties.put("clarificationSummary", Map.of("type", List.of("string", "null")));
        properties.put("imageObservations", stringArray());
        properties.put("likelyIssue", likelyIssueSchema());
        properties.put("possibleCauses", stringArray());
        properties.put("recommendedTools", stringArray());
        properties.put("recommendedParts", stringArray());
        properties.put("safetyNotes", stringArray());
        return object(properties);
    }

    private Map<String, Object> likelyIssueSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", Map.of("type", "string"));
        properties.put("confidence", Map.of("type", "number"));
        properties.put("evidence", stringArray());
        return object(properties);
    }

    private Map<String, Object> stringArray() {
        return Map.of("type", "array", "items", Map.of("type", "string"));
    }

    private Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }
}
