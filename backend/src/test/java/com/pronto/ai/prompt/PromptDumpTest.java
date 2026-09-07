package com.pronto.ai.prompt;

import com.pronto.ai.TestCategories;
import com.pronto.ai.TestTaxonomy;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the rendered system prompt to disk so its token count can be measured exactly.
 *
 * <p><b>Why this exists rather than a tokenizer dependency.</b> {@code ClassificationPromptSizeTest}
 * guards the size on every build using a characters-per-token ratio, which is a proxy. Getting the
 * real number needs a real tokenizer, and pulling a tokenizer into the backend's runtime
 * dependencies to support one occasional measurement is a bad trade. Dumping the text and counting
 * it outside the JVM costs nothing and is exact:
 *
 * <pre>
 *   PRONTO_PROMPT_DUMP=true mvn test -Dtest=PromptDumpTest
 *   python -c "import tiktoken,pathlib; \
 *     print(len(tiktoken.get_encoding('o200k_base').encode( \
 *       pathlib.Path('system-budget2.txt').read_text(encoding='utf-8'))))"
 * </pre>
 *
 * <p>{@code o200k_base} is the encoding the {@code gpt-5} family uses. This is how the v6 -> v7
 * figures (10,423 -> 9,607 tokens) were produced.
 *
 * <p>Asserts nothing and is skipped unless {@code PRONTO_PROMPT_DUMP=true}, so a normal build
 * neither runs it nor writes anything.
 */
@EnabledIfEnvironmentVariable(named = "PRONTO_PROMPT_DUMP", matches = "true")
class PromptDumpTest {

    @Test
    void dump() throws Exception {
        ClassificationPromptBuilder builder = new ClassificationPromptBuilder(TestTaxonomy.taxonomy());
        ServiceCategoryCatalog catalog = new ServiceCategoryCatalog(TestCategories.repository());

        Path out = Path.of(System.getProperty("java.io.tmpdir"), "pronto-prompt");
        Files.createDirectories(out);

        String withBudget = builder.buildSystemPrompt(catalog.categories(), 2);
        String noBudget = builder.buildSystemPrompt(catalog.categories(), 0);
        Files.writeString(out.resolve("system-budget2.txt"), withBudget, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("system-budget0.txt"), noBudget, StandardCharsets.UTF_8);

        System.out.println("PROMPT_DUMP_DIR=" + out.toAbsolutePath());
        System.out.println("PROMPT_CHARS_BUDGET2=" + withBudget.length());
        System.out.println("PROMPT_CHARS_BUDGET0=" + noBudget.length());
    }
}
