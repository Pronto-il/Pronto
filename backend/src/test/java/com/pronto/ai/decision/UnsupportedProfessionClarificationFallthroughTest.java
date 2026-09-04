package com.pronto.ai.decision;

import com.pronto.ai.TestCategories;
import com.pronto.ai.TestTaxonomy;
import com.pronto.ai.catalog.ServiceCategory;
import com.pronto.ai.catalog.ServiceCategoryCatalog;
import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clarification fall-through: asking for a question is not the same as asking one.
 *
 * <p>{@code RoutingDecisionPolicy} declined dispatch for an undispatchable trade only when the
 * model had <em>not</em> requested clarification. That exemption exists for a real case — a model
 * torn between an outside trade and a Pronto one must be able to ask rather than dead-end — but it
 * rested on an assumption that does not hold: that {@code needsClarification = true} results in a
 * question being asked.
 *
 * <p>It does not. A question is asked only if <em>all three</em> of "the evidence is ambiguous",
 * "the budget allows it" and "the question is usable" hold, and the model controls only the first.
 * When it asked for clarification and the other two failed, control ran past the dispatch-declined
 * check into the routing branches, where a proposed category became a real booking — or, failing
 * that, the {@code general_handyman} fallback.
 *
 * <p><b>Measured, not hypothetical.</b> One case in a 3,500-case evaluation run classified
 * {@code TILER} — a trade Pronto does not dispatch — set {@code needsClarification = true},
 * produced a question with too few options, and was routed to {@code general_handyman} at
 * confidence 0.20. A customer with loose floor tiles would have had a handyman sent to them, and
 * every dispatch-safety metric would have recorded it as a correct classification.
 *
 * <p>Each test below drives one of the three ways a requested question fails to be asked, plus the
 * paths that must keep working unchanged.
 */
class UnsupportedProfessionClarificationFallthroughTest {

    private RoutingDecisionPolicy policy;
    private List<ServiceCategory> categories;

    @BeforeEach
    void setUp() {
        policy = new RoutingDecisionPolicy(new RoutingProperties(), TestTaxonomy.taxonomy());
        categories = new ServiceCategoryCatalog(TestCategories.repository()).categories();
    }

    /**
     * The shape that caused the incident: an undispatchable profession, clarification requested,
     * and a category proposed alongside it. Only {@code nextQuestion} varies between the tests.
     */
    private static ClassificationResponse undispatchableAsking(ClarificationQuestion question) {
        return new ClassificationResponse(
                "רצף", "TILER", "LOOSE_TILES", null, null,
                "general_handyman", 0.20, true, "tiler or handyman",
                List.of(new CategoryCandidate("general_handyman", 0.20)),
                question);
    }

    private static ClarificationQuestion question(String text, List<String> options) {
        return new ClarificationQuestion("q1", text, options, List.of("general_handyman"));
    }

    /** A question the policy will accept — two distinct, non-blank options. */
    private static ClarificationQuestion usableQuestion() {
        return question("האריחים זזים או סדוקים?", List.of("זזים", "סדוקים"));
    }

    // ==============================================================================================
    // The three ways a requested question is not asked
    // ==============================================================================================

    @Test
    void anUnusableQuestionDoesNotConvertAnUnsupportedTradeIntoADispatchedOne() {
        // One option is not a choice, so usableQuestion() rejects it and no question is asked.
        ClassificationResponse response = undispatchableAsking(
                question("האריחים זזים?", List.of("כן")));

        RoutingDecision decision = policy.decide(response, categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        // The assertion the incident is about. Anything non-null here is a customer sent a trade
        // that cannot do the job.
        assertThat(decision.category()).isNull();
        assertThat(decision.question()).isNull();
        assertThat(decision.candidates()).isEmpty();
        // The classification itself survives — it was correct, and it is what the product records.
        assertThat(decision.professionCode()).isEqualTo("TILER");
        assertThat(decision.subcategoryCode()).isEqualTo("LOOSE_TILES");
        assertThat(decision.detectedProfession()).isEqualTo("רצף");
    }

    @ParameterizedTest(name = "unusable question ({0} option(s)) still cannot dispatch")
    @ValueSource(ints = {0, 1, 6, 7})
    void everyUnusableOptionCountIsRefusedRatherThanRouted(int optionCount) {
        List<String> options = java.util.stream.IntStream.range(0, optionCount)
                .mapToObj(i -> "אפשרות " + i)
                .toList();

        RoutingDecision decision = policy.decide(
                undispatchableAsking(question("שאלה כלשהי?", options)), categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.category()).isNull();
    }

    @Test
    void aBlankQuestionTextDoesNotConvertAnUnsupportedTradeIntoADispatchedOne() {
        RoutingDecision decision = policy.decide(
                undispatchableAsking(question("   ", List.of("כן", "לא"))), categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.category()).isNull();
    }

    @Test
    void aNullQuestionDoesNotConvertAnUnsupportedTradeIntoADispatchedOne() {
        // needsClarification = true with no question at all: the model asked and supplied nothing.
        RoutingDecision decision = policy.decide(
                undispatchableAsking(null), categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.category()).isNull();
    }

    @Test
    void aRepeatedQuestionDoesNotConvertAnUnsupportedTradeIntoADispatchedOne() {
        // Perfectly usable in isolation, but already answered — the deduplicator discards it and
        // the conversation ends. Before the fix, that ending routed to general_handyman.
        List<ClarificationExchange> prior = List.of(
                new ClarificationExchange("האריחים זזים או סדוקים?", "זזים"));

        RoutingDecision decision = policy.decide(
                undispatchableAsking(usableQuestion()), categories, prior, 1);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.category()).isNull();
    }

    @Test
    void anExhaustedBudgetDoesNotConvertAnUnsupportedTradeIntoADispatchedOne() {
        // Two questions already answered is the configured maximum, so the budget is zero and a
        // perfectly usable question cannot be asked.
        List<ClarificationExchange> prior = List.of(
                new ClarificationExchange("שאלה 1?", "תשובה 1"),
                new ClarificationExchange("שאלה 2?", "תשובה 2"));

        RoutingDecision decision = policy.decide(
                undispatchableAsking(usableQuestion()), categories, prior, 2);

        assertThat(policy.remainingBudget(2)).isZero();
        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.category()).isNull();
    }

    // ==============================================================================================
    // What must keep working
    // ==============================================================================================

    @Test
    void aUsableQuestionOnAnUndispatchableTradeStillAsks() {
        // The exemption the original check existed for, and the reason this is not fixed by simply
        // deleting the needsClarification clause: a model torn between an outside trade and a
        // Pronto one must be allowed to resolve it by asking.
        RoutingDecision decision = policy.decide(
                undispatchableAsking(usableQuestion()), categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.ASK_CLARIFICATION);
        assertThat(decision.question()).isNotNull();
        assertThat(decision.question().options()).containsExactly("זזים", "סדוקים");
        // Still no booking while the question is outstanding.
        assertThat(decision.category()).isNull();
    }

    @Test
    void anUndispatchableTradeThatAsksForNothingIsStillUnsupported() {
        // The pre-existing path, asserted here so the fix cannot regress it.
        ClassificationResponse response = new ClassificationResponse(
                "רצף", "TILER", "LOOSE_TILES", null, null,
                "general_handyman", 0.90, false, null,
                List.of(new CategoryCandidate("general_handyman", 0.90)), null);

        RoutingDecision decision = policy.decide(response, categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION);
        assertThat(decision.category()).isNull();
    }

    @Test
    void aDispatchableTradeWithAnUnusableQuestionStillRoutesNormally() {
        // The fix must not turn every unusable question into an unsupported answer. A plumber
        // whose question was rejected is still a plumber.
        ClassificationResponse response = new ClassificationResponse(
                "אינסטלטור", "PLUMBER", "CLOGGED_DRAIN", null, null,
                "plumbing", 0.91, true, "unclear",
                List.of(new CategoryCandidate("plumbing", 0.91)),
                question("סתימה?", List.of("כן")));

        RoutingDecision decision = policy.decide(response, categories, List.of(), 0);

        assertThat(decision.outcome()).isIn(RoutingDecision.Outcome.FINAL,
                RoutingDecision.Outcome.FINAL_LOW_CONFIDENCE);
        assertThat(decision.category()).isNotNull();
        assertThat(decision.category().code()).isEqualTo("plumbing");
    }

    @Test
    void anUnrecognisedProfessionWithAnUnusableQuestionStillUsesTheControlledFallback() {
        // classified == null: the model named no profession this taxonomy knows, so there is no
        // undispatchable trade to protect and the existing unresolved fallback is correct. This
        // pins that the new guard keys on the VALIDATED profession, not on the free-text label.
        ClassificationResponse response = new ClassificationResponse(
                null, "NOT_A_REAL_CODE", null, null, null,
                null, 0.10, true, "nothing usable",
                List.of(), question("?", List.of("כן")));

        RoutingDecision decision = policy.decide(response, categories, List.of(), 0);

        assertThat(decision.outcome()).isEqualTo(RoutingDecision.Outcome.FINAL_UNRESOLVED);
        assertThat(decision.category().code()).isEqualTo(ServiceCategoryCatalog.FALLBACK_CATEGORY_CODE);
    }

    /**
     * The property the whole file exists to protect, stated once as an invariant rather than as a
     * list of cases: across every combination of clarification request, question usability and
     * budget, an undispatchable profession never acquires a category.
     */
    @Test
    void noCombinationOfClarificationStateEverDispatchesAnUndispatchableTrade() {
        List<ClarificationQuestion> questions = new java.util.ArrayList<>();
        questions.add(null);
        questions.add(question("שאלה?", List.of()));
        questions.add(question("שאלה?", List.of("כן")));
        questions.add(question("  ", List.of("כן", "לא")));
        questions.add(usableQuestion());

        for (ClarificationQuestion q : questions) {
            for (boolean needsClarification : new boolean[]{true, false}) {
                for (int answered = 0; answered <= 2; answered++) {
                    ClassificationResponse response = new ClassificationResponse(
                            "רצף", "TILER", "LOOSE_TILES", null, null,
                            "general_handyman", 0.20, needsClarification, "x",
                            List.of(new CategoryCandidate("general_handyman", 0.20)), q);

                    RoutingDecision decision = policy.decide(response, categories, List.of(), answered);

                    assertThat(decision.category())
                            .as("question=%s needsClarification=%s answered=%s", q, needsClarification, answered)
                            .isNull();
                    assertThat(decision.outcome())
                            .as("question=%s needsClarification=%s answered=%s", q, needsClarification, answered)
                            .isIn(RoutingDecision.Outcome.UNSUPPORTED_PROFESSION,
                                    RoutingDecision.Outcome.ASK_CLARIFICATION);
                }
            }
        }
    }
}
