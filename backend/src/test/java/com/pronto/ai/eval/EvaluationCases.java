package com.pronto.ai.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

/**
 * Loads the labelled dataset from {@code src/test/resources/ai-eval/cases.json}.
 *
 * <p>Kept as a plain JSON file rather than Java fixtures so cases can be added by anyone
 * reasoning about routing, without touching code — growing this dataset is the actual path to
 * a trustworthy accuracy number.
 *
 * <p>The file carries its own {@link Dataset#version()}, which every evaluation run prints
 * alongside the prompt and model versions. Without it a reported accuracy figure cannot be
 * reproduced: "94%" means nothing if the set it was measured on has since been edited.
 */
public final class EvaluationCases {

    public static final String DEFAULT_RESOURCE = "/ai-eval/cases.json";

    /**
     * The dataset file: a version marker plus the cases themselves.
     *
     * @param version stable identifier for this exact set of cases and labels
     * @param notes   what the tiers mean and what has been frozen
     * @param cases   every labelled case, both tiers
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dataset(String version, String notes, List<EvaluationCase> cases) {

        public Dataset {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }

        /**
         * The approved regression set the {@code >= 95%} final-accuracy target is measured on.
         */
        public List<EvaluationCase> core() {
            return cases.stream().filter(EvaluationCase::isCore).toList();
        }

        /**
         * Deliberately adversarial / multi-trade cases, reported separately so they can
         * neither flatter the headline number nor depress it. See roadmap §23.
         */
        public List<EvaluationCase> challenge() {
            return cases.stream().filter(testCase -> !testCase.isCore()).toList();
        }
    }

    private EvaluationCases() {
    }

    /** Every case in the file, both tiers — the shape the pre-MS3 callers expect. */
    public static List<EvaluationCase> load() {
        return dataset().cases();
    }

    public static Dataset dataset() {
        return dataset(DEFAULT_RESOURCE);
    }

    public static Dataset dataset(String resourcePath) {
        try (InputStream stream = EvaluationCases.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Evaluation dataset not found on the classpath: " + resourcePath);
            }
            Dataset dataset = new ObjectMapper().readValue(stream, Dataset.class);
            if (dataset.cases().isEmpty()) {
                throw new IllegalStateException("Evaluation dataset " + resourcePath + " contains no cases.");
            }
            return dataset;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read the evaluation dataset " + resourcePath, e);
        }
    }
}
