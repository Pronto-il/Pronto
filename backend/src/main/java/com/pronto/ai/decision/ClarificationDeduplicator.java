package com.pronto.ai.decision;

import com.pronto.ai.dto.ClarificationExchange;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guards the "never ask the same thing twice" rule at the application level, rather than
 * trusting the prompt's instruction not to.
 *
 * <p>Catches both exact repeats and light rephrasings: the comparison normalises away case,
 * punctuation, Hebrew niqqud/marks and stop-ish filler, then measures token overlap. Two
 * questions whose meaningful words overlap heavily are treated as the same question, and a
 * proposed duplicate causes the pipeline to stop asking and commit instead
 * ({@link RoutingDecisionPolicy}) — which is the safe direction: worst case Pronto asks one
 * question fewer, never one more.
 *
 * <p>The threshold is intentionally strict enough that two genuinely different questions
 * about the same appliance ("where is the water coming from" vs "does the machine still
 * spin") do not collide.
 */
public final class ClarificationDeduplicator {

    /** Jaccard overlap of meaningful tokens at or above which two questions are "the same". */
    static final double DUPLICATE_TOKEN_OVERLAP = 0.7;

    private ClarificationDeduplicator() {
    }

    public static boolean isDuplicate(String candidateQuestion, List<ClarificationExchange> priorExchanges) {
        if (candidateQuestion == null || candidateQuestion.isBlank()) {
            return false;
        }
        if (priorExchanges == null || priorExchanges.isEmpty()) {
            return false;
        }

        Set<String> candidateTokens = tokenize(candidateQuestion);
        if (candidateTokens.isEmpty()) {
            return false;
        }

        for (ClarificationExchange exchange : priorExchanges) {
            Set<String> priorTokens = tokenize(exchange.question());
            if (priorTokens.isEmpty()) {
                continue;
            }
            if (normalize(candidateQuestion).equals(normalize(exchange.question()))
                    || jaccard(candidateTokens, priorTokens) >= DUPLICATE_TOKEN_OVERLAP) {
                return true;
            }
        }
        return false;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    /**
     * Strips diacritics (so Hebrew niqqud does not defeat the comparison), lowercases, and
     * reduces everything that is not a letter or digit to a single space.
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private static Set<String> tokenize(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(normalized.split(" "))
                // One- and two-character tokens in Hebrew are almost always prefixes/particles
                // (ה, ב, מ, של, את) and would inflate the overlap between unrelated questions.
                .filter(token -> token.length() > 2)
                .collect(HashSet::new, Set::add, Set::addAll);
    }
}
