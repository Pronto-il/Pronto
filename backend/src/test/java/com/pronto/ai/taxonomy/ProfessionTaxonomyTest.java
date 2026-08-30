package com.pronto.ai.taxonomy;

import com.pronto.ai.TestCategories;
import com.pronto.ai.TestTaxonomy;
import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integrity of the classification label space, and of its one bridge to the dispatch layer.
 *
 * <p>These assertions are cheap and they guard something expensive: the taxonomy is generated
 * from a spreadsheet by a script, and a regeneration that silently dropped a profession or
 * pointed a dispatch mapping at a category that no longer exists would otherwise surface as a
 * strange evaluation result weeks later, or as a runtime failure in production.
 */
class ProfessionTaxonomyTest {

    private final ProfessionTaxonomy taxonomy = TestTaxonomy.taxonomy();

    @Test
    void theTaxonomyMatchesTheSourceWorkbookShape() {
        assertThat(taxonomy.taxonomyVersion()).isNotBlank();
        assertThat(taxonomy.professions()).hasSize(50);
        assertThat(taxonomy.professions())
                .allSatisfy(profession -> assertThat(profession.subcategories()).hasSize(5));
        assertThat(taxonomy.professions().stream()
                .mapToInt(profession -> profession.subcategories().size()).sum()).isEqualTo(250);
    }

    @Test
    void professionCodesAreUniqueAndSubcategoryCodesAreUniqueWithinTheirProfession() {
        List<String> codes = taxonomy.professionCodes();
        assertThat(codes).doesNotHaveDuplicates();

        taxonomy.professions().forEach(profession ->
                assertThat(profession.subcategories().stream().map(ProfessionSubcategory::code).toList())
                        .as("subcategories of %s", profession.code())
                        .doesNotHaveDuplicates());
    }

    /**
     * The property that forces {@link ProfessionTaxonomy#findSubcategory} to take both halves.
     * If subcategory codes were globally unique this test would fail, and the pair-validation it
     * justifies could be simplified away — so it is asserted rather than assumed.
     */
    @Test
    void subcategoryCodesDeliberatelyRepeatAcrossProfessions() {
        long distinct = taxonomy.allSubcategoryCodes().size();

        assertThat(distinct)
                .as("subcategory codes are only meaningful under their profession")
                .isLessThan(250);
        assertThat(taxonomy.findSubcategory("AC_TECHNICIAN", "NOT_COOLING")).isPresent();
        assertThat(taxonomy.findSubcategory("REFRIGERATOR_TECHNICIAN", "NOT_COOLING")).isPresent();
    }

    /**
     * <b>The single most important assertion in this file.</b> A dispatch mapping pointing at a
     * category that is not in the live table would resolve to nothing at runtime, and the
     * profession would silently behave as undispatchable — a whole trade quietly falling out of
     * the product with no error anywhere.
     */
    @Test
    void everyDispatchMappingNamesARealProductionCategory() {
        Set<String> realCodes = Set.copyOf(new ServiceCategoryCatalog(TestCategories.repository())
                .categories().stream().map(ServiceCategory::code).toList());

        assertThat(realCodes).hasSize(7);
        taxonomy.dispatchable().forEach(profession ->
                assertThat(realCodes)
                        .as("%s dispatches as '%s'", profession.code(), profession.dispatchCategoryCode())
                        .contains(profession.dispatchCategoryCode()));
    }

    @Test
    void theTaxonomyIsMostlyNotDispatchableAndThatIsTheExpectedState() {
        // Recorded as a fact rather than a target. 32 of 50 professions have no category, which is
        // why UNSUPPORTED_PROFESSION had to become a first-class successful outcome rather than an
        // edge case -- if this ratio is ever read as a defect, the fix is to add categories to the
        // database, never to widen the mappings so more requests "succeed".
        assertThat(taxonomy.dispatchable()).hasSize(18);
        assertThat(taxonomy.undispatchable()).hasSize(32);
        assertThat(taxonomy.dispatchable().size() + taxonomy.undispatchable().size()).isEqualTo(50);
    }

    @ParameterizedTest(name = "{0} dispatches as {1}")
    @CsvSource({
            "PLUMBER,                    plumbing",
            // Pronto has no boiler category by design; water-heater work is plumbing's scope.
            "BOILER_TECHNICIAN,          plumbing",
            "ELECTRICIAN,                electrical",
            "AC_TECHNICIAN,              ac_hvac",
            "REFRIGERATOR_TECHNICIAN,    appliance_repair",
            "WASHING_MACHINE_TECHNICIAN, appliance_repair",
            "LOCKSMITH,                  locksmith",
            "PAINTER,                    painting",
            "HANDYMAN,                   general_handyman",
            // The door leaf, hinges and alignment are explicitly general_handyman's scope; the
            // lock is the locksmith's. This mapping is what keeps that boundary intact.
            "DOOR_TECHNICIAN,            general_handyman",
    })
    void keyDispatchMappingsAreWhatTheCategoryBoundariesAlreadySay(String profession, String category) {
        assertThat(taxonomy.dispatchCategoryCode(profession)).isEqualTo(category);
    }

    @ParameterizedTest(name = "{0} is classified but not dispatched")
    @CsvSource({"GAS_TECHNICIAN", "PEST_CONTROL", "GLAZIER", "GARDENER", "ROOFER",
            "WATERPROOFING_CONTRACTOR", "CARPENTER", "KITCHEN_INSTALLER", "COMPUTER_TECHNICIAN"})
    void tradesProntoDoesNotSellAreStillFirstClassMembersOfTheLabelSpace(String professionCode) {
        // The two halves of the requirement, asserted together because separating them is exactly
        // the mistake: the profession must EXIST (so it can be classified and counted) and must
        // NOT dispatch (so it can never be booked).
        assertThat(taxonomy.find(professionCode)).isPresent();
        assertThat(taxonomy.dispatchCategoryCode(professionCode)).isNull();
    }

    @Test
    void lookupsAreCaseInsensitiveAndRejectUnknownCodes() {
        assertThat(taxonomy.find("plumber")).isPresent();
        assertThat(taxonomy.find("PLUMBER")).isPresent();
        assertThat(taxonomy.find("NOT_A_PROFESSION")).isEmpty();
        assertThat(taxonomy.find(null)).isEmpty();
        assertThat(taxonomy.find("  ")).isEmpty();
    }

    @Test
    void aSubcategoryFromTheWrongProfessionIsRejectedRatherThanCoerced() {
        // Both codes are individually valid; the pair is not. The JSON Schema cannot express this,
        // so this is the only thing standing between the model and a stored PLUMBER/NOT_COOLING.
        assertThat(taxonomy.findSubcategory("PLUMBER", "NOT_COOLING")).isEmpty();
        assertThat(taxonomy.findSubcategory("PLUMBER", "CLOGGED_DRAIN")).isPresent();
    }

    @Test
    void theStage3TaxonomyRequirementsArePresent() {
        // Spot-checks of the specific distinctions the taxonomy redesign was asked for, so a
        // regeneration that flattened any of them fails here with the reason attached.
        assertThat(subcategoriesOf("PLUMBER"))
                .as("a burst pipe must not share a subcategory with a dripping tap")
                .contains("CLOGGED_DRAIN", "FAUCET_OR_CONNECTION_LEAK", "BURST_PIPE_OR_MAJOR_LEAK");
        assertThat(subcategoriesOf("AC_TECHNICIAN"))
                .as("heating and cooling failures stay separate")
                .contains("NOT_COOLING", "NOT_HEATING");
        assertThat(subcategoriesOf("BOILER_TECHNICIAN"))
                .as("customer-observable symptoms, not component names")
                .contains("NO_HOT_WATER", "BOILER_LEAK", "SOLAR_COLLECTOR", "WEAK_HOT_WATER_FLOW");
        assertThat(subcategoriesOf("LOCKSMITH")).contains("KEY_STUCK_OR_BROKEN", "LOCKED_OUT");
        assertThat(subcategoriesOf("DOOR_TECHNICIAN")).contains("DOOR_SCRAPING_OR_MISALIGNED", "HINGES");
        assertThat(subcategoriesOf("HANDYMAN"))
                .as("no catch-all 'small home repairs' bucket for specialists to fall into")
                .containsExactlyInAnyOrder("HANGING_AND_DRILLING", "FURNITURE_ASSEMBLY",
                        "HARDWARE_AND_HANDLES", "SILICONE_SEALING", "SMALL_HOME_INSTALLATIONS");
        assertThat(subcategoriesOf("GAS_TECHNICIAN")).contains("SUSPECTED_GAS_LEAK", "GAS_POINT");
    }

    private List<String> subcategoriesOf(String professionCode) {
        return taxonomy.find(professionCode).orElseThrow().subcategories().stream()
                .map(ProfessionSubcategory::code)
                .toList();
    }
}
