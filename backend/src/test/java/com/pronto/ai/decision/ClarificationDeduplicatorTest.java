package com.pronto.ai.decision;

import com.pronto.ai.dto.ClarificationExchange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Duplicate-question detection. The rule it protects — never ask the customer something they
 * have already answered — cannot be left to a prompt instruction, because the cost of
 * breaking it is the exact friction this redesign is meant to remove.
 *
 * <p>The threshold is tuned to be strict: two genuinely different questions about the same
 * appliance must survive, or the system would stop asking useful follow-ups.
 */
class ClarificationDeduplicatorTest {

    private List<ClarificationExchange> asked(String... questions) {
        return List.of(questions).stream()
                .map(question -> new ClarificationExchange(question, "תשובה"))
                .toList();
    }

    @Test
    void anIdenticalQuestionIsADuplicate() {
        assertThat(ClarificationDeduplicator.isDuplicate("מאיפה מגיעים המים?",
                asked("מאיפה מגיעים המים?"))).isTrue();
    }

    @Test
    void punctuationAndCasingDoNotHideADuplicate() {
        assertThat(ClarificationDeduplicator.isDuplicate("  מאיפה מגיעים המים ??? ",
                asked("מאיפה מגיעים המים?"))).isTrue();
        assertThat(ClarificationDeduplicator.isDuplicate("Where Is The Water Coming From?",
                asked("where is the water coming from"))).isTrue();
    }

    @Test
    void aLightRephrasingIsStillADuplicate() {
        assertThat(ClarificationDeduplicator.isDuplicate("האם המפסק קופץ כשמפעילים מכשירים אחרים?",
                asked("המפסק קופץ כשמפעילים מכשירים אחרים?"))).isTrue();
    }

    @Test
    void aDifferentQuestionAboutTheSameApplianceIsNotADuplicate() {
        assertThat(ClarificationDeduplicator.isDuplicate("האם המכונה עדיין מסתובבת?",
                asked("מאיפה מגיעים המים מתחת למכונה?"))).isFalse();
    }

    @Test
    void aDuplicateOfAnyEarlierQuestionCountsNotJustTheLastOne() {
        assertThat(ClarificationDeduplicator.isDuplicate("מאיפה מגיעים המים?",
                asked("מאיפה מגיעים המים?", "האם יש מים חמים?"))).isTrue();
    }

    @Test
    void nothingAskedYetMeansNothingCanBeADuplicate() {
        assertThat(ClarificationDeduplicator.isDuplicate("מאיפה מגיעים המים?", List.of())).isFalse();
        assertThat(ClarificationDeduplicator.isDuplicate("מאיפה מגיעים המים?", null)).isFalse();
    }

    @Test
    void aBlankCandidateIsNotTreatedAsADuplicate() {
        // Blank questions are rejected elsewhere as unusable; conflating the two failure modes
        // would make the log say "repeat" when the real problem was an empty question.
        assertThat(ClarificationDeduplicator.isDuplicate("   ", asked("מאיפה מגיעים המים?"))).isFalse();
    }
}
