package com.pronto.ai.eval.taxonomy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads the converted classification dataset and its manifest.
 *
 * <p>JSONL rather than one JSON document: 5,000 rows are read a line at a time, a malformed row
 * names its own line number, and a diff of the file shows which cases changed instead of one
 * enormous changed blob.
 *
 * <p><b>The split is read, never computed.</b> Every case arrives carrying the split the
 * converter froze into it, so the same row is in the same split for every prompt version, on
 * every machine, forever. A harness that re-derived splits at runtime — however deterministic
 * the function — would silently reshuffle the moment that function or its inputs changed, and
 * "V2 beat V1 on validation" would quietly stop being a comparison.
 */
public final class TaxonomyDataset {

    public static final String DATASET_RESOURCE = "/ai-eval/classification-dataset-v2.jsonl";
    public static final String MANIFEST_RESOURCE = "/ai-eval/classification-dataset-v2.manifest.json";

    /**
     * Provenance for one evaluation run, printed beside every number the harness reports.
     *
     * @param datasetSha256 the digest of the JSONL as generated. Printing it is what makes a
     *                      result falsifiable later: an accuracy figure quoted against a dataset
     *                      that has since been edited is not reproducible, and without a digest
     *                      nobody can tell that has happened.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(
            String datasetVersion,
            String taxonomyVersion,
            String sourceWorkbook,
            String splitSalt,
            int rows,
            int professions,
            int subcategories,
            int dispatchable,
            String datasetSha256
    ) {
    }

    private final List<TaxonomyEvaluationCase> cases;
    private final Manifest manifest;

    private TaxonomyDataset(List<TaxonomyEvaluationCase> cases, Manifest manifest) {
        this.cases = List.copyOf(cases);
        this.manifest = manifest;
    }

    public static TaxonomyDataset load() {
        return load(DATASET_RESOURCE, MANIFEST_RESOURCE);
    }

    public static TaxonomyDataset load(String datasetResource, String manifestResource) {
        ObjectMapper mapper = new ObjectMapper();
        List<TaxonomyEvaluationCase> cases = new ArrayList<>();

        try (InputStream stream = TaxonomyDataset.class.getResourceAsStream(datasetResource)) {
            if (stream == null) {
                throw new IllegalStateException("Classification dataset not on the classpath: "
                        + datasetResource + " — regenerate it with "
                        + "backend/tools/classification_dataset/build_dataset.py");
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    cases.add(mapper.readValue(line, TaxonomyEvaluationCase.class));
                } catch (Exception e) {
                    throw new IllegalStateException(datasetResource + " line " + lineNumber
                            + " is not a readable case", e);
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read " + datasetResource, e);
        }

        if (cases.isEmpty()) {
            throw new IllegalStateException(datasetResource + " contains no cases.");
        }

        Manifest manifest;
        try (InputStream stream = TaxonomyDataset.class.getResourceAsStream(manifestResource)) {
            if (stream == null) {
                throw new IllegalStateException("Dataset manifest not on the classpath: " + manifestResource);
            }
            manifest = mapper.readValue(stream, Manifest.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read " + manifestResource, e);
        }

        if (manifest.rows() != cases.size()) {
            throw new IllegalStateException("Manifest claims " + manifest.rows() + " rows but "
                    + datasetResource + " holds " + cases.size() + " — regenerate both together.");
        }

        return new TaxonomyDataset(cases, manifest);
    }

    public List<TaxonomyEvaluationCase> cases() {
        return cases;
    }

    public Manifest manifest() {
        return manifest;
    }

    /** The development split — the only one prompt tuning may look at. */
    public List<TaxonomyEvaluationCase> dev() {
        return split(TaxonomyEvaluationCase.SPLIT_DEV);
    }

    /** The validation split: for comparing candidate prompt versions against each other. */
    public List<TaxonomyEvaluationCase> validation() {
        return split(TaxonomyEvaluationCase.SPLIT_VALIDATION);
    }

    /**
     * The holdout split.
     *
     * <p><b>Do not look at these while tuning.</b> Not a technical restriction — nothing here can
     * stop a determined caller — but the whole reason the split exists. A prompt iterated against
     * a set until it scores well has been fitted to that set, and its accuracy on it stops
     * predicting anything about new customers. The holdout is spent the first time it is used to
     * choose between two prompts, and there is no way to un-spend it.
     */
    public List<TaxonomyEvaluationCase> holdout() {
        return split(TaxonomyEvaluationCase.SPLIT_HOLDOUT);
    }

    public List<TaxonomyEvaluationCase> split(String split) {
        return cases.stream()
                .filter(testCase -> testCase.split().equalsIgnoreCase(split))
                .toList();
    }

    /**
     * A deterministic, stratified subsample: up to {@code perGroup} cases from each
     * (profession, subcategory) group, lowest dataset ID first.
     *
     * <p>Exists because the dataset is 5,000 live model calls and a smoke run should not be.
     * Taking the first N by ID rather than sampling randomly keeps two runs comparable, and
     * stratifying keeps every profession represented — a random 250 of 5,000 would miss whole
     * professions and produce accuracy figures for a label space it never tested.
     */
    public static List<TaxonomyEvaluationCase> stratifiedSample(List<TaxonomyEvaluationCase> cases,
                                                                 int perGroup) {
        java.util.Map<String, Integer> taken = new java.util.LinkedHashMap<>();
        List<TaxonomyEvaluationCase> sampled = new ArrayList<>();
        for (TaxonomyEvaluationCase testCase : cases.stream()
                .sorted(java.util.Comparator.comparingInt(TaxonomyEvaluationCase::id))
                .toList()) {
            String key = testCase.expectedProfession() + "/" + testCase.expectedSubcategory();
            int count = taken.getOrDefault(key, 0);
            if (count < perGroup) {
                taken.put(key, count + 1);
                sampled.add(testCase);
            }
        }
        return List.copyOf(sampled);
    }

    /** Resolves a split name from an environment variable, defaulting to {@code dev}. */
    public static String splitOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return TaxonomyEvaluationCase.SPLIT_DEV;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
