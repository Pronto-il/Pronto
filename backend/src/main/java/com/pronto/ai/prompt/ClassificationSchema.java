package com.pronto.ai.prompt;

import com.pronto.ai.taxonomy.Intent;
import com.pronto.ai.taxonomy.ProfessionTaxonomy;
import com.pronto.ai.taxonomy.Urgency;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
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

    private final ProfessionTaxonomy taxonomy;

    public ClassificationSchema(ProfessionTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    /**
     * @param categoryCodes the live {@code categories.code} values — the DISPATCH enum, seven
     *                      values. The classification enums come from the injected taxonomy and
     *                      are deliberately not passed in: they are a fixed, versioned label
     *                      space rather than a snapshot of a database table.
     */
    public Map<String, Object> build(List<String> categoryCodes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        // Free text, and that is the entire point: it is the one field in this schema not bounded
        // by Pronto's catalogue, so the model can name the trade the customer actually needs even
        // when Pronto does not offer it. Constraining it to an enum would recreate exactly the
        // forcing this field exists to remove. It is a label for the customer and for telemetry;
        // it is never matched against anything and can never become a routing target.
        properties.put("detectedProfession", Map.of("type", "string"));
        // ---- the CLASSIFICATION layer: constrained to the versioned profession taxonomy ----
        //
        // Nullable, and that nullability is load-bearing in the opposite direction to
        // primaryCategoryCode's. A null category means "Pronto cannot dispatch this", which is a
        // routine and correct answer. A null professionCode means "I could not place this request
        // in the taxonomy at all", which is rare and is a signal the taxonomy has a gap. Forcing
        // the model to pick the least-wrong profession would hide exactly that signal.
        properties.put("professionCode", nullableEnum(taxonomy.professionCodes()));
        properties.put("subcategoryCode", nullableEnum(taxonomy.allSubcategoryCodes()));
        properties.put("intent", nullableEnum(names(Intent.values())));
        properties.put("urgency", nullableEnum(names(Urgency.values())));
        // ---- the DISPATCH layer: constrained to the live categories table ----
        properties.put("primaryCategoryCode", nullableEnum(categoryCodes));
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

    /**
     * A string constrained to {@code values}, or {@code null}.
     *
     * <p>{@code null} is added to the enum as well as to the type union: under {@code strict}
     * every property stays {@code required}, so "may be absent" has to be expressed as "may be
     * null", and an enum that omitted {@code null} would contradict the type union it sits
     * beside.
     */
    private Map<String, Object> nullableEnum(List<String> values) {
        List<Object> withNull = new ArrayList<>(values);
        withNull.add(null);
        return Map.of("type", List.of("string", "null"), "enum", withNull);
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
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
