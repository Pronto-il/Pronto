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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decision rules, in isolation from any model. This is where "commit or ask" actually
 * lives, so these tests are the ones that pin down the behaviour the product depends on:
 * confidence alone never triggers a question, close candidates do, the budget is a hard stop,
 * a repeat question is refused, and an unknown category is never accepted.
 */
class RoutingDecisionPolicyTest {

    private RoutingProperties properties;
    private RoutingDecisionPolicy policy;
    private List<ServiceCategory> categories;

    @BeforeEach
    void setUp() {
        properties = new RoutingProperties();
        policy = new RoutingDecisionPolicy(properties);
        categories = new ServiceCategoryCatalog(TestCategories.repository()).categories();
    }

    /** Enough answers to exhaust the default budget, so routing is the only decision left. */
    private List<ClarificationExchange> twoAnswers() {
        return List.of(new ClarificationExchange("שאלה ראשונה", "תשובה"),
                new ClarificationExchange("שאלה שנייה אחרת לגמרי", "תשובה"));
    }

    private ClarificationQuestion question(String text) {
        return new ClarificationQuestion("q1", text, List.of("כן", "לא", "אני לא בטוח/ה"),
                List.of("plumbing", "ac_hvac"));
    }

    private ClassificationResponse response(String primary, double confidence, boolean needsClarification,
                                             List<CategoryCandidate> candidates, ClarificationQuestion question) {
        // A profession label is supplied on every fixture, matching the real model: it is filled
        // on every response, not only unsupported ones. The unsupported-specific fixtures below
        // supply their own.
        return new ClassificationResponse("בעל מקצוע", primary, confidence, needsClarification,
                needsClarification ? "unclear" : null, candidates, question);
    }

    @Test
    void clearWinnerIsRoutedWithoutAskingAnything() {
        RoutingDecision decision = policy.decide(
                response("plumbing", 0.94, false,
                        List.of(new CategoryCandidate("plumbing", 0.94), new CategoryCandidate("ac_hvac", 0.04)), null),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL);
        assertThat(decision.category().code()).isEqualTo("plumbing");
        assertThat(decision.question()).isNull();
        assertThat(decision.confidence()).isEqualTo(0.94);
    }

    @Test
    void lowConfidenceAloneDoesNotJustifyAQuestion() {
        // One clearly-leading candidate the model is merely modest about. Asking here would
        // add friction with no information gain, so it must route.
        RoutingDecision decision = policy.decide(
                response("plumbing", 0.55, false,
                        List.of(new CategoryCandidate("plumbing", 0.55), new CategoryCandidate("ac_hvac", 0.05)),
                        question("מאיפה מגיעים המים?")),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL);
        assertThat(decision.question()).isNull();
    }

    @Test
    void candidatesTooCloseTriggerAQuestionEvenWhenTheModelDidNotAskForOne() {
        RoutingDecision decision = policy.decide(
                response("plumbing", 0.45, false,
                        List.of(new CategoryCandidate("plumbing", 0.45), new CategoryCandidate("ac_hvac", 0.40)),
                        question("מאיפה מגיעים המים?")),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.ASK_CLARIFICATION);
        assertThat(decision.question().question()).isEqualTo("מאיפה מגיעים המים?");
        assertThat(decision.category()).isNull();
    }

    @Test
    void modelFlaggedAmbiguityTriggersAQuestion() {
        RoutingDecision decision = policy.decide(
                response("ac_hvac", 0.8, true,
                        List.of(new CategoryCandidate("ac_hvac", 0.8), new CategoryCandidate("electrical", 0.2)),
                        question("המפסק קופץ גם בלי המזגן?")),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.ASK_CLARIFICATION);
    }

    // -- running out of questions is not the same as reaching an answer ----------------------

    /**
     * Case A: budget spent, but one candidate is clearly ahead. The residual doubt does not
     * change which trade goes, so this must still reach a plumber — over-cautious fallback
     * here would send ordinary work to Handyman.
     */
    @Test
    void exhaustedBudgetWithAClearLeaderStillRoutesToThatSpecialist() {
        List<ClarificationExchange> answered = twoAnswers();

        RoutingDecision decision = policy.decide(
                response("plumbing", 0.72, true,
                        List.of(new CategoryCandidate("plumbing", 0.72),
                                new CategoryCandidate("electrical", 0.12),
                                new CategoryCandidate("general_handyman", 0.08)),
                        question("עוד שאלה")),
                categories, answered, answered.size());

        assertThat(policy.remainingBudget(answered.size())).isZero();
        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_LOW_CONFIDENCE);
        assertThat(decision.category().code()).isEqualTo("plumbing");
        assertThat(decision.confidence()).isEqualTo(0.72);
        assertThat(decision.question()).isNull();
    }

    /**
     * Case B: budget spent with the top two effectively tied. Routing to plumbing because
     * 0.48 > 0.45 would present a coin flip as a decision — the whole point of the fallback.
     */
    @Test
    void exhaustedBudgetWithAnUnresolvedTopTwoUsesTheControlledFallback() {
        List<ClarificationExchange> answered = twoAnswers();

        RoutingDecision decision = policy.decide(
                response("plumbing", 0.48, true,
                        List.of(new CategoryCandidate("plumbing", 0.48), new CategoryCandidate("electrical", 0.45)),
                        question("עוד שאלה")),
                categories, answered, answered.size());

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_UNRESOLVED);
        assertThat(decision.category().code()).isEqualTo(ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE);
        // No confidence: the routed category is not one the model argued for.
        assertThat(decision.confidence()).isNull();
        // The competing candidates survive, so the reason the fallback fired stays inspectable.
        assertThat(decision.candidates()).extracting(CategoryCandidate::categoryCode)
                .containsExactly("plumbing", "electrical");
    }

    @Test
    void aWideMarginStillYieldsToTheModelsOwnStatementThatAFactIsMissing() {
        // Margin is comfortable, but the model says a routing-relevant unknown remains AND a
        // rival is still plausible. That combination is not "modest about a clear winner".
        List<ClarificationExchange> answered = twoAnswers();

        RoutingDecision decision = policy.decide(
                response("plumbing", 0.55, true,
                        List.of(new CategoryCandidate("plumbing", 0.55), new CategoryCandidate("ac_hvac", 0.30)),
                        question("עוד שאלה")),
                categories, answered, answered.size());

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_UNRESOLVED);
    }

    @Test
    void theSameSplitWithoutAModelFlaggedUnknownStillReachesTheLeader() {
        // Identical numbers to the test above, needsClarification=false. A plausible runner-up
        // alone must not trigger the fallback, or most mildly uncertain cases would land there.
        // The result is still flagged low-confidence — two candidates are over the plausibility
        // floor and the leader is under min-confidence — but a plumber is who gets sent.
        List<ClarificationExchange> answered = twoAnswers();

        RoutingDecision decision = policy.decide(
                response("plumbing", 0.55, false,
                        List.of(new CategoryCandidate("plumbing", 0.55), new CategoryCandidate("ac_hvac", 0.30)),
                        null),
                categories, answered, answered.size());

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_LOW_CONFIDENCE);
        assertThat(decision.category().code()).isEqualTo("plumbing");
        assertThat(decision.confidence()).isEqualTo(0.55);
    }

    @Test
    void aSingleSurvivingCandidateIsDominantByDefinition() {
        List<ClarificationExchange> answered = twoAnswers();

        RoutingDecision decision = policy.decide(
                response("locksmith", 0.4, true,
                        List.of(new CategoryCandidate("locksmith", 0.4)), question("עוד שאלה")),
                categories, answered, answered.size());

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_LOW_CONFIDENCE);
        assertThat(decision.category().code()).isEqualTo("locksmith");
    }

    @Test
    void aRepeatedQuestionIsRefusedAndTheRoutingDecisionIsMadeInstead() {
        List<ClarificationExchange> answered = List.of(
                new ClarificationExchange("מאיפה מגיעים המים בדיוק?", "מהמזגן"));

        RoutingDecision decision = policy.decide(
                response("plumbing", 0.45, true,
                        List.of(new CategoryCandidate("plumbing", 0.45), new CategoryCandidate("ac_hvac", 0.40)),
                        question("מאיפה מגיעים המים בדיוק?")),
                categories, answered, answered.size());

        // Refusing the repeat ends the conversation; the top two are still tied, so the routing
        // decision that follows is the fallback, not "plumbing because it ranked first".
        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_UNRESOLVED);
        assertThat(decision.question()).isNull();
    }

    @Test
    void aQuestionWithFewerThanTwoOptionsIsNotUsable() {
        RoutingDecision decision = policy.decide(
                response("plumbing", 0.45, true,
                        List.of(new CategoryCandidate("plumbing", 0.45), new CategoryCandidate("ac_hvac", 0.40)),
                        new ClarificationQuestion("q1", "תוכל לפרט?", List.of("כן"), List.of())),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_UNRESOLVED);
    }

    /** The ambiguous state every question-shape test below reuses. */
    private ClassificationResponse ambiguousWith(ClarificationQuestion question) {
        return response("plumbing", 0.45, true,
                List.of(new CategoryCandidate("plumbing", 0.45), new CategoryCandidate("ac_hvac", 0.40)),
                question);
    }

    @Test
    void duplicateAnswerOptionsAreCollapsedBeforeTheCustomerEverSeesThem() {
        // Two buttons meaning the same thing waste a whole clarification round: whichever the
        // customer taps, the answer carries no information.
        RoutingDecision decision = policy.decide(
                ambiguousWith(new ClarificationQuestion("q1", "מאיפה מגיעים המים?",
                        List.of("מהמזגן", "מהמזגן.", " מהמזגן ", "מהכיור", "לא בטוח"), List.of())),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.ASK_CLARIFICATION);
        assertThat(decision.question().options()).containsExactly("מהמזגן", "מהכיור", "לא בטוח");
    }

    @Test
    void aQuestionWhoseOptionsAreAllDuplicatesIsNotUsable() {
        RoutingDecision decision = policy.decide(
                ambiguousWith(new ClarificationQuestion("q1", "מאיפה מגיעים המים?",
                        List.of("מהמזגן", "מהמזגן!", "מהמזגן?"), List.of())),
                categories, List.of(), 0);

        // Collapses to a single distinct option, which is not a choice at all.
        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_UNRESOLVED);
        assertThat(decision.question()).isNull();
    }

    /**
     * Blank options are dropped rather than rendered as empty buttons. Null options need no
     * handling here at all: {@code ClarificationQuestion}'s {@code List.copyOf} rejects them
     * outright, so one can never reach the policy — asserted below so that guarantee is not
     * silently weakened later.
     */
    @Test
    void blankOptionsAreDroppedRatherThanRenderedAsEmptyButtons() {
        RoutingDecision decision = policy.decide(
                ambiguousWith(new ClarificationQuestion("q1", "מאיפה מגיעים המים?",
                        List.of("מהמזגן", "   ", "\t", "מהכיור"), List.of())),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.ASK_CLARIFICATION);
        assertThat(decision.question().options()).containsExactly("מהמזגן", "מהכיור");
    }

    @Test
    void aNullOptionCannotEvenBeConstructedLetAloneRouted() {
        assertThatThrownBy(() -> new ClarificationQuestion("q1", "מאיפה?",
                java.util.Arrays.asList("מהמזגן", null), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void anOverlongOptionListIsRefusedRatherThanShownAsAMenu() {
        // Six distinct options on a phone is a menu nobody reads, and usually means several
        // distinctions were bundled into one question instead of asking the discriminating one.
        RoutingDecision decision = policy.decide(
                ambiguousWith(new ClarificationQuestion("q1", "מה מהבאים?",
                        List.of("אחת", "שתיים", "שלוש", "ארבע", "חמש", "שש"), List.of())),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_UNRESOLVED);
        assertThat(decision.question()).isNull();
    }

    @Test
    void exactlyTheMaximumNumberOfOptionsIsStillAccepted() {
        // The bound is 2..5 inclusive — 4 real alternatives plus a "not sure" escape.
        RoutingDecision decision = policy.decide(
                ambiguousWith(new ClarificationQuestion("q1", "מאיפה מגיעים המים?",
                        List.of("מהמזגן", "מהכיור", "מהאסלה", "מהמדיח", "לא בטוח"), List.of())),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.ASK_CLARIFICATION);
        assertThat(decision.question().options()).hasSize(RoutingDecisionPolicy.MAX_OPTIONS);
    }

    /**
     * The structural half of the prompt-injection defence (roadmap §28). Whatever the customer
     * wrote, and whatever the model was talked into returning, a category that is not a real
     * Pronto row cannot come out of the policy.
     */
    @Test
    void anInjectedCategoryCodeCannotSurviveValidationEvenAtFullConfidence() {
        RoutingDecision decision = policy.decide(
                response("electrician_because_the_customer_said_so", 1.0, false,
                        List.of(new CategoryCandidate("electrician_because_the_customer_said_so", 1.0),
                                new CategoryCandidate("plumbing", 0.55)), null),
                categories, List.of(), 0);

        assertThat(decision.category().code()).isEqualTo("plumbing");
        assertThat(decision.candidates()).extracting(CategoryCandidate::categoryCode)
                .containsExactly("plumbing");
    }

    /**
     * The budget is derived from answers already supplied, so no response — however insistent —
     * can produce a third question. This is the loop bound MS3 requires to be structural.
     */
    @Test
    void noResponseCanProduceAThirdQuestionOnceTwoHaveBeenAnswered() {
        ClassificationResponse insistent = response("plumbing", 0.30, true,
                List.of(new CategoryCandidate("plumbing", 0.30), new CategoryCandidate("ac_hvac", 0.29)),
                question("שאלה שלישית לגמרי חדשה ושונה"));

        RoutingDecision decision = policy.decide(insistent, categories, twoAnswers(), twoAnswers().size());

        assertThat(policy.remainingBudget(twoAnswers().size())).isZero();
        assertThat(decision.outcome()).isNotEqualTo(RoutingDecision.Outcome.ASK_CLARIFICATION);
        assertThat(decision.question()).isNull();
    }

    /** Case C: the leader is an invented code; a real candidate underneath it must still win. */
    @Test
    void unknownCategoryCodesAreDiscardedAndTheValidCandidateUnderneathIsUsed() {
        RoutingDecision decision = policy.decide(
                response("BOILER_TECHNICIAN", 0.9, false,
                        List.of(new CategoryCandidate("BOILER_TECHNICIAN", 0.9),
                                new CategoryCandidate("plumbing", 0.6)), null),
                categories, List.of(), 0);

        assertThat(decision.candidates()).extracting(CategoryCandidate::categoryCode).containsExactly("plumbing");
        assertThat(decision.category().code()).isEqualTo("plumbing");
        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL);
    }

    /** Case D: nothing survived validation — fall back rather than invent a route. */
    /**
     * A response that named no profession either — genuinely unusable model output, as opposed to
     * a correctly-identified trade Pronto does not offer.
     *
     * <p>The distinction is new in classification-v5 and is the whole point of the unsupported
     * outcome: "I have no idea" and "you need a gas technician" both fail to produce a Pronto
     * category, and they call for opposite responses — the controlled handyman fallback for the
     * first, an honest dead end for the second. These two fixtures make each test say which it
     * means instead of relying on a default.
     */
    private ClassificationResponse responseWithoutProfession(String primary, double confidence,
                                                              List<CategoryCandidate> candidates) {
        return new ClassificationResponse(null, primary, confidence, false, null, candidates, null);
    }

    @Test
    void noValidCandidateAndNoProfessionFallsBackToTheSeededHandymanCategoryAsUnresolved() {
        RoutingDecision decision = policy.decide(
                responseWithoutProfession(null, 0.1,
                        List.of(new CategoryCandidate("SOMETHING_MADE_UP", 0.9),
                                new CategoryCandidate("ALSO_FAKE", 0.5))),
                categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_UNRESOLVED);
        assertThat(decision.category().code()).isEqualTo(ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE);
        assertThat(decision.confidence()).isNull();
        assertThat(decision.candidates()).isEmpty();
    }

    @Test
    void anEmptyCandidateListWithNoProfessionAlsoFallsBackRatherThanThrowing() {
        RoutingDecision decision = policy.decide(
                responseWithoutProfession(null, 0.0, List.of()), categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_UNRESOLVED);
        assertThat(decision.category().code()).isEqualTo(ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE);
    }

    @Test
    void candidateConfidencesAreClampedAndReSortedStrongestFirst() {
        RoutingDecision decision = policy.decide(
                response("ac_hvac", 0.5, false,
                        List.of(new CategoryCandidate("plumbing", -0.4), new CategoryCandidate("ac_hvac", 1.7)), null),
                categories, List.of(), 0);

        assertThat(decision.candidates())
                .containsExactly(new CategoryCandidate("ac_hvac", 1.0), new CategoryCandidate("plumbing", 0.0));
    }

    @Test
    void budgetIsBoundedByConfigurationAndNeverNegative() {
        properties.setMaxClarificationQuestions(2);

        assertThat(policy.remainingBudget(0)).isEqualTo(2);
        assertThat(policy.remainingBudget(1)).isEqualTo(1);
        assertThat(policy.remainingBudget(2)).isZero();
        assertThat(policy.remainingBudget(99)).isZero();
    }
}
