package com.pronto.ai.prompt;

import com.pronto.ai.TestCategories;
import com.pronto.ai.TestTaxonomy;
import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.taxonomy.Intent;
import com.pronto.ai.taxonomy.Profession;
import com.pronto.ai.taxonomy.ProfessionTaxonomy;
import com.pronto.ai.taxonomy.Urgency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code classification-v6} prompt and schema: does the model actually get told about the
 * label space, and is it constrained to it?
 *
 * <p>The failure mode worth guarding is silent and total — a prompt that renders the taxonomy
 * from an empty list, or a schema whose enum is missing, still produces a perfectly valid request
 * and a plausible-looking answer. Nothing errors; the classification is simply unconstrained, and
 * the first sign would be an evaluation run full of professions that do not exist.
 */
class ClassificationPromptTaxonomyTest {

    private final ProfessionTaxonomy taxonomy = TestTaxonomy.taxonomy();
    private final ClassificationPromptBuilder builder = new ClassificationPromptBuilder(taxonomy);
    private final List<ServiceCategory> categories =
            new ServiceCategoryCatalog(TestCategories.repository()).categories();

    @Test
    void everyProfessionAndSubcategoryReachesThePrompt() {
        String prompt = builder.buildSystemPrompt(categories, 2);

        for (Profession profession : taxonomy.professions()) {
            assertThat(prompt).as("profession %s", profession.code()).contains(profession.code());
            assertThat(prompt).as("Hebrew name of %s", profession.code()).contains(profession.nameHe());
            profession.subcategories().forEach(subcategory ->
                    assertThat(prompt).as("%s/%s", profession.code(), subcategory.code())
                            .contains(subcategory.code()));
        }
    }

    @Test
    void theDispatchMarkerDistinguishesServedFromUnservedTrades() {
        String prompt = builder.buildSystemPrompt(categories, 2);

        // Built from the taxonomy rather than written out, so the assertion does not depend on
        // Hebrew appearing in this source file in a particular bidirectional order.
        Profession plumber = taxonomy.find("PLUMBER").orElseThrow();
        Profession gas = taxonomy.find("GAS_TECHNICIAN").orElseThrow();

        assertThat(prompt).contains("PLUMBER (" + plumber.nameHe() + ") [dispatched as plumbing]");
        assertThat(prompt).contains("GAS_TECHNICIAN (" + gas.nameHe() + ") [not dispatched by Pronto]");
        // ...and the instruction that stops the marker being read as a selection criterion.
        // Both phrases are chosen to sit within one rendered line: the prompt is built from text
        // blocks, so a substring spanning a wrap never matches however correct it looks here.
        assertThat(prompt).contains("information about Pronto, NOT about the customer");
        assertThat(prompt).contains("picking a dispatched profession over the correct one");
    }

    @Test
    void theTwoLayersAreNamedAndOrdered() {
        String prompt = builder.buildSystemPrompt(categories, 2);

        assertThat(prompt.indexOf("PROFESSION TAXONOMY (the classification label space)"))
                .as("the label space is introduced before the dispatch catalogue")
                .isLessThan(prompt.indexOf("AVAILABLE CATEGORIES (the dispatch layer)"));
        assertThat(prompt).contains("A correct classification Pronto cannot dispatch");
    }

    @Test
    void intentAndUrgencyAreDefinedWithTheirEnumValuesAndTheOverUseWarning() {
        String prompt = builder.buildSystemPrompt(categories, 2);

        for (Intent intent : Intent.values()) {
            assertThat(prompt).as("intent %s", intent).contains(intent.name());
        }
        for (Urgency urgency : Urgency.values()) {
            assertThat(prompt).as("urgency %s", urgency).contains(urgency.name());
        }
        // The specific defect the dataset shows: 246 rows say "דחוף" and are labelled NORMAL.
        assertThat(prompt).contains("DO NOT OVERUSE THE TOP OF EITHER SCALE");
        assertThat(prompt).contains("דחוף");
    }

    @Test
    void theStage3ProfessionBoundariesArePresent() {
        String prompt = builder.buildSystemPrompt(categories, 2);

        assertThat(prompt).contains("PROFESSION BOUNDARIES");
        assertThat(prompt).contains("PAINTER ONLY when the customer states the leak is ALREADY FIXED");
        assertThat(prompt).contains("LOCKSMITH vs DOOR_TECHNICIAN");
        assertThat(prompt).contains("HANDYMAN is a scope, not a shortcut");
        assertThat(prompt).contains("CARPENTER vs KITCHEN_INSTALLER");
        assertThat(prompt).contains("Kitchen context is the deciding signal");
        assertThat(prompt).contains("LEAK_DETECTION");
        // Safety: escalate and stop, never improvise instructions.
        assertThat(prompt).contains("Do NOT write troubleshooting steps");
    }

    @Test
    void theSchemaConstrainsClassificationToTheTaxonomyAndDispatchToTheLiveCategories() {
        Map<String, Object> schema = new ClassificationSchema(taxonomy)
                .build(categories.stream().map(ServiceCategory::code).toList());

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(enumOf(properties, "professionCode"))
                .containsAll(taxonomy.professionCodes())
                .hasSize(taxonomy.professionCodes().size() + 1);   // + null
        assertThat(enumOf(properties, "subcategoryCode"))
                .containsAll(taxonomy.allSubcategoryCodes());
        assertThat(enumOf(properties, "intent")).contains("REPAIR", "EMERGENCY", "DIAGNOSIS");
        assertThat(enumOf(properties, "urgency")).contains("LOW", "NORMAL", "HIGH", "CRITICAL");

        // The dispatch enum stays the seven live categories -- the two layers have different
        // vocabularies and the schema is where that is enforced.
        assertThat(enumOf(properties, "primaryCategoryCode"))
                .containsExactlyInAnyOrder("plumbing", "electrical", "ac_hvac", "appliance_repair",
                        "locksmith", "painting", "general_handyman", null);

        // Under strict mode every property must remain required, so "optional" is expressed as a
        // nullable type union rather than by omission.
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).contains("professionCode", "subcategoryCode", "intent", "urgency");
        assertThat(schema).containsEntry("additionalProperties", false);
    }

    /**
     * The prompt is sent on every classification and 5,000 times per full evaluation run, so its
     * size is a real cost — roughly 42,000 characters, or about 11k tokens, as of
     * {@code classification-v6}. That is up sharply from v5: the 50-profession taxonomy and the
     * profession-boundary section together account for most of the increase.
     *
     * <p>Asserted as a loose ceiling rather than an exact figure. The point is to catch an
     * accidental order-of-magnitude blow-up — a boundary section rendered once per profession,
     * say — not to freeze the wording, which is expected to change with every prompt version.
     *
     * <p>Worth knowing rather than worth panicking about: the system prompt is identical across
     * every call with the same category list and budget, so it is exactly the shape provider-side
     * prompt caching is built for. If cost becomes a problem, the first thing to measure is
     * whether trimming the seven long category-boundary profiles — which now partly duplicate the
     * profession boundaries — buys anything.
     */
    @Test
    void thePromptStaysWithinAReasonableSize() {
        String prompt = builder.buildSystemPrompt(categories, 2);

        assertThat(prompt.length())
                .as("prompt is %d characters", prompt.length())
                .isBetween(10_000, 55_000);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> enumOf(Map<String, Object> properties, String field) {
        return (List<Object>) ((Map<String, Object>) properties.get(field)).get("enum");
    }
}
