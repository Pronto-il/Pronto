package com.pronto.ai.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

/**
 * Loads the labelled dataset from {@code src/test/resources/ai-eval/cases.json}.
 *
 * <p>Kept as a plain JSON file rather than Java fixtures so cases can be added by anyone
 * reasoning about routing, without touching code — growing this dataset is the actual path to
 * a trustworthy accuracy number.
 */
public final class EvaluationCases {

    public static final String DEFAULT_RESOURCE = "/ai-eval/cases.json";

    private EvaluationCases() {
    }

    public static List<EvaluationCase> load() {
        return load(DEFAULT_RESOURCE);
    }

    public static List<EvaluationCase> load(String resourcePath) {
        try (InputStream stream = EvaluationCases.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Evaluation dataset not found on the classpath: " + resourcePath);
            }
            return new ObjectMapper().readValue(stream, new TypeReference<List<EvaluationCase>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read the evaluation dataset " + resourcePath, e);
        }
    }
}
