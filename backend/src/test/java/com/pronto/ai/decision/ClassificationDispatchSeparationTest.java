package com.pronto.ai.decision;

import com.pronto.ai.TestCategories;
import com.pronto.ai.TestTaxonomy;
import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationResponse;
import com.pronto.ai.taxonomy.Intent;
import com.pronto.ai.taxonomy.Urgency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The load-bearing separation: <b>classification is one question, dispatch is another, and a
 * correct classification Pronto cannot serve is a success.</b>
 *
 * <p>The failure these tests exist to prevent is specific and quiet. A model that names
 * {@code GAS_TECHNICIAN} and then, trying to be useful, also offers {@code plumbing} as a
 * candidate would — under the pre-taxonomy policy — produce a perfectly ordinary
 * {@code CLASSIFIED} result, a booking, and a plumber arriving at a gas fault. Nothing would look
 * wrong in any log or metric; the classification was right and the dispatch was catastrophic.
 *
 * <p>So the taxonomy's dispatch mapping overrides whatever categories the model proposes. The
 * model may be wrong about which category fits a trade; it cannot be right about a category for
 * a trade Pronto does not sell, because there isn't one.
 */
class ClassificationDispatchSeparationTest {

    private RoutingDecisionPolicy policy;
    private List<ServiceCategory> categories;

    @BeforeEach
    void setUp() {
        policy = new RoutingDecisionPolicy(new RoutingProperties(), TestTaxonomy.taxonomy());
        categories = new ServiceCategoryCatalog(TestCategories.repository()).categories();
    }

    private static ClassificationResponse classified(String professionCode, String subcategoryCode,
                                                      String proposedCategory, double confidence) {
        List<CategoryCandidate> candidates = proposedCategory == null ? List.of()
                : List.of(new CategoryCandidate(proposedCategory, confidence));
        return new ClassificationResponse("טכנאי", professionCode, subcategoryCode, Intent.REPAIR,
                Urgency.NORMAL, proposedCategory, confidence, false, null, candidates, null);
    }

    // ---- an undispatchable profession is never forced into a category -------------------------

    @ParameterizedTest(name = "{0} is classified, not dispatched, even though the model offered {1}")
    @CsvSource({
            "GAS_TECHNICIAN,           plumbing",
            "GAS_TECHNICIAN,           general_handyman",
            "PEST_CONTROL,             general_handyman",
            "GLAZIER,                  general_handyman",
            "ROOFER,                   plumbing",
            "WATERPROOFING_CONTRACTOR, painting",
            "CARPENTER,                general_handyman",
            "KITCHEN_INSTALLER,        general_handyman",
            "COMPUTER_TECHNICIAN,      electrical",
    })
    void anUndispatchableProfessionDiscardsTheCategoriesTheModelProposed(String professionCode,
                                                                          String proposedCategory) {
        ClassificationResponse response = classified(professionCode, null, proposedCategory, 0.94);

        RoutingDecision decision = policy.decide(response, categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        // The assertion that matters. The model proposed a real, resolvable category and it was
        // thrown away rather than honoured.
        assertThat(decision.category()).isNull();
        assertThat(decision.candidates()).isEmpty();
        // ...and the classification survived intact. That is what makes this a success, not a
        // failure, and it is what the evaluation harness scores.
        assertThat(decision.professionCode()).isEqualTo(professionCode);
    }

    @Test
    void theClassificationLayerSurvivesOntoAnUndispatchableOutcome() {
        ClassificationResponse response = new ClassificationResponse("טכנאי גז", "GAS_TECHNICIAN",
                "SUSPECTED_GAS_LEAK", Intent.EMERGENCY, Urgency.CRITICAL, null, 0.97, false, null,
                List.of(), null);

        RoutingDecision decision = policy.decide(response, categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.professionCode()).isEqualTo("GAS_TECHNICIAN");
        assertThat(decision.subcategoryCode()).isEqualTo("SUSPECTED_GAS_LEAK");
        assertThat(decision.intent()).isEqualTo(Intent.EMERGENCY);
        assertThat(decision.urgency()).isEqualTo(Urgency.CRITICAL);
        assertThat(decision.confidence()).isEqualTo(0.97);
        assertThat(decision.category()).isNull();
    }

    @ParameterizedTest(name = "confidence {0} does not change the dispatch answer")
    @ValueSource(doubles = {0.05, 0.42, 0.71, 0.99})
    void dispatchIsIndependentOfConfidence(double confidence) {
        RoutingDecision decision = policy.decide(
                classified("PEST_CONTROL", "COCKROACHES", "general_handyman", confidence),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.category()).isNull();
    }

    // ---- dispatchable professions keep working exactly as before ------------------------------

    @ParameterizedTest(name = "{0} still dispatches as {1}")
    @CsvSource({
            "PLUMBER,                    plumbing",
            "BOILER_TECHNICIAN,          plumbing",
            "LEAK_DETECTION,             plumbing",
            "SEWAGE_TANKER,              plumbing",
            "ELECTRICIAN,                electrical",
            "AC_TECHNICIAN,              ac_hvac",
            "REFRIGERATOR_TECHNICIAN,    appliance_repair",
            "WASHING_MACHINE_TECHNICIAN, appliance_repair",
            "LOCKSMITH,                  locksmith",
            "PAINTER,                    painting",
            "HANDYMAN,                   general_handyman",
            "DOOR_TECHNICIAN,            general_handyman",
    })
    void aDispatchableProfessionRoutesNormally(String professionCode, String categoryCode) {
        // The regression this guard most plausibly introduces: over-triggering, and quietly
        // dead-ending trades Pronto genuinely sells. A refrigerator technician IS appliance_repair.
        RoutingDecision decision = policy.decide(
                classified(professionCode, null, categoryCode, 0.93), categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL);
        assertThat(decision.category().code()).isEqualTo(categoryCode);
        assertThat(decision.professionCode()).isEqualTo(professionCode);
    }

    // ---- ambiguity still wins over the dispatch guard -----------------------------------------

    /**
     * The one case where an undispatchable profession must NOT dead-end.
     *
     * <p>A gas smell near a gas water heater is genuinely either the supply (unsupported) or the
     * heater (plumbing). Dead-ending on the model's first guess would tell a customer Pronto
     * cannot help before they had the chance to say which it was — so when the model is asking,
     * the guard stands down and the question is asked.
     */
    @Test
    void agenuinelyAmbiguousUndispatchableTradeStillAsksInsteadOfDeadEnding() {
        ClassificationResponse ambiguous = new ClassificationResponse("טכנאי גז", "GAS_TECHNICIAN",
                "SUSPECTED_GAS_LEAK", Intent.DIAGNOSIS, Urgency.HIGH, "plumbing", 0.5, true,
                "could be the gas supply or the water heater",
                List.of(new CategoryCandidate("plumbing", 0.5)),
                new ClarificationQuestion("q1", "האם הריח מגיע מהדוד עצמו או מהצנרת?",
                        List.of("מהדוד", "מהצנרת", "לא בטוח"), List.of("plumbing")));

        RoutingDecision decision = policy.decide(ambiguous, categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.ASK_CLARIFICATION);
        assertThat(decision.question()).isNotNull();
        assertThat(decision.professionCode()).isEqualTo("GAS_TECHNICIAN");
    }

    // ---- validation of the classification layer itself ----------------------------------------

    @Test
    void anInventedProfessionCodeIsDroppedRatherThanTrusted() {
        ClassificationResponse response = classified("WIZARD", "MAGIC", "plumbing", 0.9);

        RoutingDecision decision = policy.decide(response, categories, List.of(), 0);

        assertThat(decision.professionCode()).isNull();
        // With no recognised profession the dispatch guard cannot fire, so routing falls through to
        // the ordinary path and the customer still reaches a plumber. Degrading to the old
        // behaviour is the right failure mode here.
        assertThat(decision.category().code()).isEqualTo("plumbing");
    }

    @Test
    void aSubcategoryFromTheWrongProfessionIsDroppedButTheProfessionSurvives() {
        RoutingDecision decision = policy.decide(
                classified("PLUMBER", "NOT_COOLING", "plumbing", 0.9), categories, List.of(), 0);

        assertThat(decision.professionCode()).isEqualTo("PLUMBER");
        assertThat(decision.subcategoryCode())
                .as("NOT_COOLING is a real code, but not under PLUMBER")
                .isNull();
        assertThat(decision.category().code()).isEqualTo("plumbing");
    }

    @Test
    void aValidPairIsKept() {
        RoutingDecision decision = policy.decide(
                classified("PLUMBER", "BURST_PIPE_OR_MAJOR_LEAK", "plumbing", 0.96),
                categories, List.of(), 0);

        assertThat(decision.professionCode()).isEqualTo("PLUMBER");
        assertThat(decision.subcategoryCode()).isEqualTo("BURST_PIPE_OR_MAJOR_LEAK");
    }

    /**
     * The pre-taxonomy path still works. A model that returns no {@code professionCode} at all —
     * an older prompt, a schema that did not apply — must route exactly as it did before, because
     * the guard has nothing to act on.
     */
    @Test
    void aResponseWithNoProfessionCodeRoutesAsItAlwaysDid() {
        ClassificationResponse legacy = new ClassificationResponse("אינסטלטור", "plumbing", 0.95,
                false, null, List.of(new CategoryCandidate("plumbing", 0.95)), null);

        RoutingDecision decision = policy.decide(legacy, categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL);
        assertThat(decision.category().code()).isEqualTo("plumbing");
        assertThat(decision.professionCode()).isNull();
    }
}
