package com.pronto.ai.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model is configuration, and stays configuration.
 *
 * <p>Pronto's classification quality depends on which model runs, so "which model?" is a question
 * that gets asked during incidents and during evaluation. Two properties make it answerable, and
 * both are the kind that decay silently:
 *
 * <ul>
 *   <li><b>Exactly one place names a model.</b> The moment a model id appears in Java — a default
 *       parameter, a fallback in a catch block, a constant "for local testing" — changing models
 *       stops being a config change, and the evaluation harness starts measuring something other
 *       than what Production runs.</li>
 *   <li><b>The default equals Production's value.</b> {@code application.yml} supplies a default,
 *       so {@code OPENAI_MODEL} is never actually absent. If that default drifted from the
 *       deployed value, an eval run without the variable set would produce a number for a model
 *       nobody is serving — and it would be quoted as Production's accuracy.</li>
 * </ul>
 *
 * <p>Reads the real files rather than a Spring context on purpose: the claim is about what is
 * written down, and a context test would happily pass while a hardcoded fallback sat unused three
 * lines away in a branch nothing exercised.
 */
class OpenAiModelConfigurationTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java/com/pronto");
    private static final String EXPECTED_MODEL = "gpt-5-mini";

    /**
     * Any {@code gpt-*} / {@code o1-*} / {@code claude-*} style identifier appearing in a string
     * literal. Broad on purpose — the failure being prevented is "somebody hardcoded a model", and
     * a pattern narrow enough to only catch today's naming would miss tomorrow's.
     */
    private static final Pattern MODEL_LITERAL =
            Pattern.compile("\"(gpt-[\\w.]+|o\\d-[\\w.]+|claude-[\\w.-]+)\"");

    @Test
    void noModelNameIsHardcodedAnywhereInJava() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(OpenAiModelConfigurationTest::modelLiteralsIn)
                    .toList();

            assertThat(offenders)
                    .as("A model id belongs in application.yml and infra/terraform only. Reaching it "
                            + "through the pronto.openai.model property is what makes swapping models a "
                            + "config change rather than a deploy of new code.")
                    .isEmpty();
        }
    }

    @Test
    void theModelIsReadFromTheOpenAiModelEnvironmentVariable() throws IOException {
        String value = openAiModelProperty();

        assertThat(value)
                .as("pronto.openai.model must be sourced from OPENAI_MODEL, so an operator can change "
                        + "models without a code change")
                .startsWith("${OPENAI_MODEL:");
    }

    @Test
    void theConfiguredDefaultIsTheModelProductionRuns() throws IOException {
        // If these ever diverge, `mvn test -Dtest=OpenAiClassificationEvaluationRunnerTest` without
        // an explicit OPENAI_MODEL measures a model nobody is serving.
        assertThat(openAiModelProperty()).isEqualTo("${OPENAI_MODEL:" + EXPECTED_MODEL + "}");
    }

    @Test
    void productionTerraformPinsTheSameModel() throws IOException {
        Path compute = Path.of("../infra/terraform/compute.tf");
        assertThat(Files.exists(compute))
                .as("compute.tf is where Production's OPENAI_MODEL is set; adjust this test if it moves")
                .isTrue();

        String terraform = Files.readString(compute, StandardCharsets.UTF_8);

        assertThat(terraform)
                .as("Production must run the model this repository is evaluated against")
                .contains("{ name = \"OPENAI_MODEL\", value = \"" + EXPECTED_MODEL + "\" }");
    }

    @SuppressWarnings("unchecked")
    private static String openAiModelProperty() throws IOException {
        try (InputStream yaml = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            Map<String, Object> root = new Yaml().load(yaml);
            Map<String, Object> pronto = (Map<String, Object>) root.get("pronto");
            Map<String, Object> openai = (Map<String, Object>) pronto.get("openai");
            return String.valueOf(openai.get("model"));
        }
    }

    /**
     * Model-shaped literals in one file, excluding <b>capability prefixes</b>.
     *
     * <p>The carve-out is deliberately narrow: a literal is ignored only when it is the argument
     * of a {@code startsWith(...)} call. That is the shape of "does this model FAMILY support
     * parameter X", which is a fact about the provider's API, and it is not the shape of "use
     * model Y", which is deployment configuration and is what this test exists to forbid.
     *
     * <p>The concrete case is {@code OpenAiChatClient.supportsCustomTemperature}: {@code gpt-5*}
     * and the o-series reject a custom {@code temperature} with a non-retryable 400, so the client
     * must know which family it is talking to. Note that this does not weaken the guarantee the
     * test protects — switching models is still {@code OPENAI_MODEL} and nothing else. What the
     * predicate cannot do is select a model, and a {@code startsWith} test cannot.
     */
    private static Stream<String> modelLiteralsIn(Path file) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            return source.lines()
                    .filter(line -> !line.contains("startsWith("))
                    .flatMap(line -> MODEL_LITERAL.matcher(line).results()
                            .map(result -> file.getFileName() + " -> " + result.group()))
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
    }
}
