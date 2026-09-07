package com.pronto.ai.prompt;

import com.pronto.ai.TestCategories;
import com.pronto.ai.TestTaxonomy;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the size of the rendered classification prompt.
 *
 * <p><b>Why this is worth a test.</b> The system prompt is sent in full on every classification
 * and on every clarification round, so its length is a per-request cost paid by every customer.
 * It is also the single easiest thing in this codebase to grow by accident: every future
 * boundary rule, worked example and clarifying sentence is individually reasonable, and the
 * cumulative effect is what took it to 10.4k tokens before anyone measured it. A ceiling turns
 * "the prompt got long again" from something nobody notices into a failing build.
 *
 * <p><b>Measured in characters, documented in tokens.</b> There is no tokenizer on the backend
 * classpath and adding a dependency to count tokens in a test would be a poor trade. The ratio is
 * stable for this text — measured with {@code tiktoken}'s {@code o200k_base} encoding, the
 * encoding {@code gpt-5-mini} uses — at close to {@value #CHARS_PER_TOKEN} characters per token,
 * mostly because the prompt is dominated by ASCII SCREAMING_SNAKE_CASE codes. Characters are a
 * faithful proxy here and cost nothing to check.
 */
class ClassificationPromptSizeTest {

    /**
     * Measured, not assumed: 42,306 characters encoded to 10,423 {@code o200k_base} tokens on the
     * v6 prompt; the v7 rewrite measured 38,488 characters to 9,607 tokens, a ratio of 4.01.
     * v7 sits slightly lower because what it removed was English prose, leaving the ASCII code
     * lists — which tokenize less efficiently — a larger share of the whole.
     */
    private static final double CHARS_PER_TOKEN = 4.01;

    /**
     * The ceiling, in characters.
     *
     * <p>Set with real headroom above the current size rather than pinned to it. A test that
     * failed on every one-line wording change would be reverted within a week; this one exists to
     * catch a section being added, not a sentence being rephrased. Adding a genuinely needed rule
     * and raising this deliberately is a fine outcome — silently doubling the prompt is not.
     */
    private static final int MAX_PROMPT_CHARS = 40_000;

    private String render(int budget) {
        return new ClassificationPromptBuilder(TestTaxonomy.taxonomy())
                .buildSystemPrompt(new ServiceCategoryCatalog(TestCategories.repository()).categories(), budget);
    }

    @Test
    void thePromptStaysWithinItsBudget() {
        String prompt = render(2);
        int approximateTokens = (int) (prompt.length() / CHARS_PER_TOKEN);

        // Printed so a run of this test is also a measurement, not only an assertion.
        System.out.printf("classification prompt: %d chars, ~%d tokens (%s)%n",
                prompt.length(), approximateTokens, ClassificationPromptBuilder.PROMPT_VERSION);

        assertThat(prompt.length()).isLessThanOrEqualTo(MAX_PROMPT_CHARS);
        assertThat(approximateTokens)
                .isCloseTo(ClassificationPromptBuilder.APPROXIMATE_PROMPT_TOKENS,
                        org.assertj.core.data.Offset.offset(600));
    }

    /**
     * The commit-now variant is the one sent on the LAST round of every clarified issue, so it
     * must be bounded too — and it must not somehow be the larger of the two.
     */
    @Test
    void theCommitNowVariantIsNoLarger() {
        assertThat(render(0).length()).isLessThanOrEqualTo(render(2).length());
        assertThat(render(0).length()).isLessThanOrEqualTo(MAX_PROMPT_CHARS);
    }

    /**
     * The compaction's actual contract: it removed WORDS, not COVERAGE.
     *
     * <p>This is the test that makes a v6-to-v7 accuracy comparison meaningful. Shortening prose
     * is safe; quietly dropping a profession, a category or a boundary rule while shortening
     * prose would change what the model is being asked, and the resulting accuracy number would
     * be measuring a different task rather than a faster one.
     */
    @Test
    void everyProfessionCategoryAndContestedBoundarySurvivedTheCompaction() {
        String prompt = render(2);

        assertThat(TestTaxonomy.taxonomy().professions()).isNotEmpty();
        TestTaxonomy.taxonomy().professions().forEach(profession -> {
            assertThat(prompt).contains(profession.code());
            profession.subcategories()
                    .forEach(subcategory -> assertThat(prompt).contains(subcategory.code()));
        });

        new ServiceCategoryCatalog(TestCategories.repository()).categories()
                .forEach(category -> assertThat(prompt).contains(category.code()));

        // The boundary headings that carry the confusion pairs the taxonomy cannot express on its
        // own — the moisture family above all, which is where committing confidently is actively
        // harmful.
        assertThat(prompt).contains(
                List.of("Moisture, damp and stains", "LOCKSMITH vs DOOR_TECHNICIAN",
                        "HANDYMAN is a scope", "CARPENTER vs KITCHEN_INSTALLER",
                        "Appliance specialists", "GAS_TECHNICIAN and safety"));

        // And the output contract, which is what the structured-output schema is validated against.
        assertThat(prompt).contains("professionCode", "subcategoryCode", "primaryCategoryCode",
                "candidates", "needsClarification", "detectedProfession");
    }
}
