package com.pronto.ai.taxonomy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The single source of truth for Pronto's <b>classification</b> label space: 50 professions and
 * the 250 subcategories beneath them, loaded once from
 * {@code src/main/resources/ai/profession-taxonomy.json}.
 *
 * <p><b>This is not a second copy of the category taxonomy.</b> The two answer different
 * questions and are deliberately different sizes:
 *
 * <table>
 *   <caption>The two layers</caption>
 *   <tr><th></th><th>this class</th><th>{@code catalog.ServiceCategoryCatalog}</th></tr>
 *   <tr><td>question</td><td>what does the customer need?</td><td>what can Pronto send?</td></tr>
 *   <tr><td>source</td><td>authored JSON resource</td><td>the live {@code categories} table</td></tr>
 *   <tr><td>size</td><td>50 professions</td><td>7 categories</td></tr>
 * </table>
 *
 * <p>{@link #dispatchCategoryCode} is the one bridge between them, and it is a <em>lookup</em>,
 * never a fallback. When a profession has no mapping the correct outcome is the existing
 * {@code UNSUPPORTED_PROFESSION} flow: Pronto classified the request correctly and cannot serve
 * it. Substituting the nearest dispatchable trade would turn a right answer into a wasted visit,
 * which is precisely what {@code prompt.ClassificationPromptBuilder}'s "professions Pronto does
 * not cover" section forbids.
 *
 * <p><b>Why a resource file rather than Java literals like
 * {@code catalog.CategoryRoutingProfiles}.</b> That class holds seven hand-reasoned routing
 * boundaries whose prose is the point. This holds 250 mechanically-derived rows generated from
 * the product's source workbook by
 * {@code backend/tools/classification_dataset/build_dataset.py}, which fails loudly if the two
 * ever disagree. Transcribing them into Java would add 250 opportunities for silent drift and
 * make that check impossible.
 *
 * <p>Validated at construction — duplicate codes, empty subcategory lists and a blank taxonomy
 * version all fail startup rather than producing a schema enum with a hole in it.
 */
@Component
public class ProfessionTaxonomy {

    public static final String RESOURCE_PATH = "/ai/profession-taxonomy.json";

    private final String taxonomyVersion;
    private final List<Profession> professions;
    private final Map<String, Profession> byCode;

    public ProfessionTaxonomy() {
        this(RESOURCE_PATH);
    }

    ProfessionTaxonomy(String resourcePath) {
        TaxonomyDocument document = load(resourcePath);

        if (document.taxonomyVersion() == null || document.taxonomyVersion().isBlank()) {
            throw new IllegalStateException(resourcePath + " has no taxonomyVersion; an evaluation "
                    + "result that cannot name the taxonomy it was measured against is not reproducible.");
        }
        if (document.professions().isEmpty()) {
            throw new IllegalStateException(resourcePath + " contains no professions.");
        }

        Map<String, Profession> index = new LinkedHashMap<>();
        List<Profession> ordered = document.professions().stream()
                .sorted(Comparator.comparingInt(Profession::priorityRank))
                .toList();

        for (Profession profession : ordered) {
            if (profession.code() == null || profession.code().isBlank()) {
                throw new IllegalStateException(resourcePath + " contains a profession with no code.");
            }
            if (profession.subcategories().isEmpty()) {
                throw new IllegalStateException("Profession " + profession.code() + " has no subcategories; "
                        + "a profession the model can name but never qualify is a hole in the label space.");
            }
            if (index.putIfAbsent(profession.code(), profession) != null) {
                throw new IllegalStateException("Duplicate profession code in " + resourcePath + ": "
                        + profession.code());
            }
            List<String> subcategoryCodes = new ArrayList<>();
            for (ProfessionSubcategory subcategory : profession.subcategories()) {
                if (subcategoryCodes.contains(subcategory.code())) {
                    throw new IllegalStateException("Duplicate subcategory code '" + subcategory.code()
                            + "' under profession " + profession.code());
                }
                subcategoryCodes.add(subcategory.code());
            }
        }

        this.taxonomyVersion = document.taxonomyVersion();
        this.professions = ordered;
        this.byCode = Map.copyOf(index);
    }

    /**
     * Identifies this exact label space in evaluation output and telemetry, alongside the prompt
     * version and the model. All three are needed: an accuracy figure moves when the taxonomy
     * changes just as surely as when the prompt does, and a number that cannot name all three
     * cannot be compared with another one.
     */
    public String taxonomyVersion() {
        return taxonomyVersion;
    }

    /** Every profession, in the workbook's priority order. */
    public List<Profession> professions() {
        return professions;
    }

    /** Profession codes only — the contents of the structured-output schema enum. */
    public List<String> professionCodes() {
        return professions.stream().map(Profession::code).toList();
    }

    /**
     * Every distinct subcategory code across all professions.
     *
     * <p>Distinct rather than qualified because subcategory codes repeat by design
     * ({@code NOT_COOLING} under both the AC and refrigerator technicians). The schema constrains
     * the model to this flat set; the <em>pair</em> is what
     * {@link #findSubcategory(String, String)} validates, since only the pair is meaningful.
     */
    public List<String> allSubcategoryCodes() {
        return professions.stream()
                .flatMap(profession -> profession.subcategories().stream())
                .map(ProfessionSubcategory::code)
                .distinct()
                .sorted()
                .toList();
    }

    /** Case-insensitive lookup. Empty when the code is not a real profession. */
    public Optional<Profession> find(String professionCode) {
        if (professionCode == null || professionCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byCode.get(professionCode.trim().toUpperCase(Locale.ROOT)));
    }

    /**
     * The subcategory, only if it genuinely belongs to that profession.
     *
     * <p>A model that returns {@code PLUMBER} with {@code NOT_COOLING} has produced two
     * individually-valid enum values and one meaningless pair; the schema cannot express that
     * constraint, so it is checked here and the subcategory is dropped rather than stored.
     */
    public Optional<ProfessionSubcategory> findSubcategory(String professionCode, String subcategoryCode) {
        if (subcategoryCode == null || subcategoryCode.isBlank()) {
            return Optional.empty();
        }
        String normalized = subcategoryCode.trim().toUpperCase(Locale.ROOT);
        return find(professionCode).flatMap(profession -> profession.subcategories().stream()
                .filter(subcategory -> subcategory.code().equalsIgnoreCase(normalized))
                .findFirst());
    }

    /**
     * The {@code categories.code} this profession is dispatched under, or {@code null} when
     * Pronto does not currently serve it.
     *
     * <p>{@code null} is a real answer, not a missing one. See the class Javadoc.
     */
    public String dispatchCategoryCode(String professionCode) {
        return find(professionCode).map(Profession::dispatchCategoryCode).orElse(null);
    }

    /** Professions Pronto can currently send someone for. */
    public List<Profession> dispatchable() {
        return professions.stream().filter(Profession::isDispatchable).toList();
    }

    /**
     * Professions Pronto classifies correctly and cannot currently serve.
     *
     * <p>Exposed because it is a product signal, not an error list: it is the demand Pronto is
     * turning away, and the shortlist for which category to add to the {@code categories} table
     * next.
     */
    public List<Profession> undispatchable() {
        return professions.stream().filter(profession -> !profession.isDispatchable()).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TaxonomyDocument(String taxonomyVersion, List<Profession> professions) {
        TaxonomyDocument {
            professions = professions == null ? List.of() : List.copyOf(professions);
        }
    }

    private static TaxonomyDocument load(String resourcePath) {
        try (InputStream stream = ProfessionTaxonomy.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Profession taxonomy not found on the classpath: "
                        + resourcePath);
            }
            return new ObjectMapper().readValue(stream, TaxonomyDocument.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read the profession taxonomy " + resourcePath, e);
        }
    }
}
