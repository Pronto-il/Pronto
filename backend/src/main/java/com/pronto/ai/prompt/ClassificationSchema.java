package com.pronto.ai.prompt;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schema for {@code dto.ClassificationResponse}, handed to OpenAI Structured Outputs
 * with {@code strict: true} so the model cannot return a shape the parser has to guess at,
 * and cannot invent a category code — {@code primaryCategoryCode} and every candidate's
 * {@code categoryCode} are enums built from the live {@code categories} table.
 *
 * <p><b>Deliberately omits numeric bounds and array length limits.</b> Those keywords are not
 * reliably supported across models in strict mode, and a rejected request is a worse failure
 * than an out-of-range value: confidence bounds, candidate counts and the
 * "needsClarification implies a usable question" rule are all enforced in Java instead
 * ({@code client.ClassificationResponseParser} and {@code decision.RoutingDecisionPolicy}),
 * which is where they have to live anyway since a schema cannot express them all.
 *
 * <p>Nullable fields use the documented {@code ["string", "null"]} type-union pattern: under
 * {@code strict} every property must stay listed in {@code required}, but its value may be
 * {@code null}.
 */
@Component
public class ClassificationSchema {

    public static final String SCHEMA_NAME = "pronto_issue_routing";

    public Map<String, Object> build(List<String> categoryCodes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("primaryCategoryCode", nullableCategoryCode(categoryCodes));
        properties.put("confidence", Map.of("type", "number"));
        properties.put("needsClarification", Map.of("type", "boolean"));
        properties.put("ambiguityReason", Map.of("type", List.of("string", "null")));
        properties.put("candidates", Map.of(
                "type", "array",
                "items", candidateSchema(categoryCodes)));
        properties.put("nextQuestion", questionSchema(categoryCodes));

        return object(properties);
    }

    private Map<String, Object> candidateSchema(List<String> categoryCodes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("categoryCode", Map.of("type", "string", "enum", categoryCodes));
        properties.put("confidence", Map.of("type", "number"));
        return object(properties);
    }

    /** Nullable object — the model returns {@code null} when it has nothing worth asking. */
    private Map<String, Object> questionSchema(List<String> categoryCodes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("question", Map.of("type", "string"));
        properties.put("options", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("distinguishesBetween", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", categoryCodes)));

        Map<String, Object> schema = new LinkedHashMap<>(object(properties));
        schema.put("type", List.of("object", "null"));
        return schema;
    }

    private Map<String, Object> nullableCategoryCode(List<String> categoryCodes) {
        List<Object> withNull = new ArrayList<>(categoryCodes);
        withNull.add(null);
        return Map.of("type", List.of("string", "null"), "enum", withNull);
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
