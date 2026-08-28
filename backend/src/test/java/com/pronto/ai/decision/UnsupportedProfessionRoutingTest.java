package com.pronto.ai.decision;

import com.pronto.ai.TestCategories;
import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Which profession?" is answered before "which Pronto category?", and the two answers are
 * allowed to disagree.
 *
 * <p>Before this behaviour existed, a customer who needed a gas technician got a plumber or a
 * handyman with a confident-looking number attached — not because the model was wrong, but because
 * the schema gave it no way to say "none of these". These tests pin down the three properties that
 * were easiest to get wrong while fixing that:
 *
 * <ul>
 *   <li><b>The catalogue decides, not the model.</b> There is no self-reported "isSupported" flag
 *       anywhere in the response for the policy to trust.</li>
 *   <li><b>Unsupported is not low confidence</b>, and does not touch the {@code lowConfidence} or
 *       {@code unresolved} flags that measure routing quality.</li>
 *   <li><b>Unsupported is not ambiguity</b>, so it never spends a clarification question — while
 *       a genuinely ambiguous case still asks one.</li>
 * </ul>
 */
class UnsupportedProfessionRoutingTest {

    private RoutingDecisionPolicy policy;
    private List<ServiceCategory> categories;

    @BeforeEach
    void setUp() {
        policy = new RoutingDecisionPolicy(new RoutingProperties());
        categories = new ServiceCategoryCatalog(TestCategories.repository()).categories();
    }

    /**
     * What the model returns for a trade outside the catalogue: the profession named, no category,
     * and — critically — <b>no candidates</b>. The empty list is how "none of these fit" is said;
     * a near-miss candidate would resolve and make this a supported case.
     */
    private static ClassificationResponse outOfCatalogue(String profession, double confidence) {
        return new ClassificationResponse(profession, null, confidence, false, null, List.of(), null);
    }

    // ---- 3 & 5. An unsupported profession is never forced into a supported category ----

    @ParameterizedTest(name = "{0} is reported as unsupported, not routed")
    @ValueSource(strings = {"טכנאי גז", "מדביר", "זגג", "גנן", "מוביל", "טכנאי אנטנות", "אטם גגות"})
    void aTradeProntoDoesNotOfferIsReportedAsUnsupported(String profession) {
        RoutingDecision decision = policy.decide(outOfCatalogue(profession, 0.95), categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.detectedProfession()).isEqualTo(profession);
        // The assertion that matters most in this file. A null category is the only honest answer;
        // general_handyman here would be the exact forcing this whole change removes.
        assertThat(decision.category()).isNull();
    }

    @Test
    void anUnsupportedProfessionIsNotRoutedToTheHandymanFallback() {
        RoutingDecision decision = policy.decide(outOfCatalogue("טכנאי גז", 0.97), categories, List.of(), 0);

        assertThat(decision.category()).isNull();
        assertThat(decision.outcome()).isNotEqualTo(RoutingDecision.Outcome.FINAL_UNRESOLVED);
        // FINAL_UNRESOLVED would have produced exactly this category. Named explicitly so the test
        // fails loudly if the two paths are ever merged.
        assertThat(ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE).isEqualTo("general_handyman");
    }

    // ---- 4. High confidence must not produce a clarification question ----

    @Test
    void aConfidentUnsupportedProfessionAsksNoQuestionEvenWithFullBudget() {
        RoutingDecision decision = policy.decide(outOfCatalogue("מדביר", 0.98), categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.question()).isNull();
        assertThat(policy.remainingBudget(0)).isEqualTo(2);  // budget was available and went unspent
    }

    @Test
    void anUnsupportedProfessionAsksNoQuestionEvenIfTheModelSuppliedOne() {
        // Defence in depth. If the model ignores the prompt and flags ambiguity on an
        // out-of-catalogue trade, the policy must still not ask — the customer would answer a
        // question and be told the same thing, having been given false hope in between.
        ClassificationResponse confused = new ClassificationResponse("טכנאי גז", null, 0.9, true,
                "model flagged ambiguity it should not have", List.of(),
                new ClarificationQuestion("q1", "האם יש ריח חזק?", List.of("כן", "לא", "לא בטוח"),
                        List.of("plumbing")));

        RoutingDecision decision = policy.decide(confused, categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.question()).isNull();
    }

    @ParameterizedTest(name = "confidence {0} still yields UNSUPPORTED_PROFESSION")
    @ValueSource(doubles = {0.05, 0.31, 0.55, 0.70, 0.98, 1.0})
    void unsupportedIsIndependentOfConfidence(double confidence) {
        // There is deliberately no threshold in the unsupported branch. Confidence describes how
        // sure the model is about the TRADE; whether Pronto sells that trade is a different fact,
        // and mixing them would make a low-confidence gas job silently become a handyman job.
        RoutingDecision decision = policy.decide(outOfCatalogue("זגג", confidence), categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.confidence()).isEqualTo(confidence);
    }

    // ---- 7. Supported and unsupported stay distinct ----

    @ParameterizedTest(name = "\"{1}\" maps to {0} and stays supported")
    @CsvSource({
            "appliance_repair, טכנאי מקררים",
            "appliance_repair, טכנאי מכונות כביסה",
            "plumbing,         אינסטלטור",
            "ac_hvac,          טכנאי מזגנים",
            "electrical,       חשמלאי",
            "locksmith,        מנעולן",
    })
    void aSpecialistTradeThatDOESMapStaysSupported(String categoryCode, String profession) {
        // The counterweight to the cases above, and the regression this feature most plausibly
        // introduces: "the profession has a specialist-sounding name" must NOT become a synonym for
        // "unsupported". A refrigerator technician is a real, specific trade AND is exactly what
        // appliance_repair covers.
        ClassificationResponse mapped = new ClassificationResponse(profession, categoryCode, 0.93, false,
                null, List.of(new CategoryCandidate(categoryCode, 0.93)), null);

        RoutingDecision decision = policy.decide(mapped, categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL);
        assertThat(decision.category().code()).isEqualTo(categoryCode);
        // The profession survives onto a supported decision too — it is telemetry on every path,
        // not a field that only exists for the dead end.
        assertThat(decision.detectedProfession()).isEqualTo(profession);
    }

    @Test
    void theCatalogueDecidesSupportEvenWhenTheModelClaimsACategory() {
        // The model names a trade Pronto does not offer AND proposes a code that is not a real
        // category (an invented one, or one from a category since removed from the table). The
        // catalogue is the only authority: nothing resolves, so this is unsupported — no self-report
        // in the response could have made it supported.
        ClassificationResponse invented = new ClassificationResponse("טכנאי גז", "gas_technician", 0.9,
                false, null, List.of(new CategoryCandidate("gas_technician", 0.9)), null);

        RoutingDecision decision = policy.decide(invented, categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.category()).isNull();
    }

    @Test
    void addingTheCategoryToTheCatalogueWouldMakeTheSameResponseSupported() {
        // The single-source-of-truth property, demonstrated rather than asserted about: the SAME
        // model response is unsupported against a catalogue without the category and supported
        // against one with it. No code changes between the two calls — which is what "the catalogue
        // decides" has to mean to be worth claiming.
        ClassificationResponse response = new ClassificationResponse("צבע", "painting", 0.9, false, null,
                List.of(new CategoryCandidate("painting", 0.9)), null);

        List<ServiceCategory> withoutPainting = categories.stream()
                .filter(category -> !category.code().equals("painting"))
                .toList();

        assertThat(policy.decide(response, withoutPainting, List.of(), 0).outcome())
                .isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(policy.decide(response, categories, List.of(), 0).outcome())
                .isEqualTo(RoutingDecision.Outcome.FINAL);
    }

    // ---- 2. Genuine ambiguity still asks, including across the supported boundary ----

    @Test
    void ambiguityBetweenAProntoTradeAndAnOutsideOneStillAsks() {
        // The one gas-adjacent case that IS ambiguity: a smell near the boiler could be the gas
        // supply (unsupported) or the water heater (plumbing, supported). The model says so by
        // including plumbing as a candidate — which resolves, so this never reaches the unsupported
        // branch and is handled as ordinary ambiguity.
        ClassificationResponse borderline = new ClassificationResponse("טכנאי גז או אינסטלטור", "plumbing",
                0.5, true, "gas supply vs the water heater itself", List.of(new CategoryCandidate("plumbing", 0.5)),
                new ClarificationQuestion("q1", "הריח מגיע מהדוד עצמו או מהצנרת?",
                        List.of("מהדוד", "מהצנרת", "אני לא בטוח/ה"), List.of("plumbing")));

        RoutingDecision decision = policy.decide(borderline, categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.ASK_CLARIFICATION);
        assertThat(decision.question()).isNotNull();
    }

    @Test
    void anUnsupportedProfessionIsStillUnsupportedAfterTheBudgetIsSpent() {
        // Reaching the unsupported branch must not depend on having budget left, or a customer who
        // answered two questions and then described a gas leak would land in the handyman fallback.
        List<ClarificationExchange> answered = List.of(
                new ClarificationExchange("שאלה ראשונה", "תשובה"),
                new ClarificationExchange("שאלה שנייה", "תשובה"));

        RoutingDecision decision = policy.decide(outOfCatalogue("מדביר", 0.9), categories, answered, 2);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.category()).isNull();
    }

    // ---- housekeeping on the free-text label ----

    @Test
    void aBlankProfessionIsTreatedAsAbsentAndFallsBackRatherThanDeadEnding() {
        // "" is not a trade name. Showing "we don't cover  " to a customer would be worse than the
        // handyman fallback, which at least reaches a real person.
        RoutingDecision decision = policy.decide(
                new ClassificationResponse("   ", null, 0.4, false, null, List.of(), null),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_UNRESOLVED);
        assertThat(decision.category().code()).isEqualTo(ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE);
    }

    @Test
    void anOverlongProfessionIsTruncatedRatherThanRejected() {
        // This string is rendered inside a Hebrew sentence on the customer's screen. A runaway
        // generation should degrade to a clipped label, not take down the classification.
        String rambling = "טכנאי ".repeat(40);

        RoutingDecision decision = policy.decide(outOfCatalogue(rambling, 0.9), categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.detectedProfession().length())
                .isLessThanOrEqualTo(RoutingDecisionPolicy.MAX_PROFESSION_LENGTH);
    }
}
