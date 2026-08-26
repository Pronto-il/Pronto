package com.pronto.ai.eval;

import java.util.List;

/**
 * One clarification exchange, captured with the classification state on <b>both sides</b> of
 * it — which is the only way to answer the question MS3 actually cares about: did asking this
 * help?
 *
 * <p>Aggregate clarification rate alone cannot distinguish a question that flipped the routing
 * from one that produced a shrug and cost the customer a screen. Recording the top candidate,
 * its confidence and the margin over the runner-up before and after the answer makes
 * {@link #changedTopCandidate()}, {@link #increasedMargin()} and {@link #increasedConfidence()}
 * computable per question rather than guessed at in aggregate.
 *
 * @param question       the question as the customer saw it
 * @param options        the answer options offered
 * @param answer         the scripted answer supplied for this labelled case
 * @param answerWasScripted {@code false} when the dataset had no matching answer and the
 *                       harness fell back to "not sure" — a gap in the dataset, and a reason
 *                       to read this round's usefulness with suspicion
 * @param topBefore      strongest candidate before the answer
 * @param topAfter       strongest candidate after re-classification
 * @param confidenceBefore confidence of {@code topBefore}
 * @param confidenceAfter  confidence of {@code topAfter}
 * @param marginBefore   gap between the top two candidates before, {@code 0} when only one
 * @param marginAfter    the same gap after
 */
public record ClarificationRound(
        String question,
        List<String> options,
        String answer,
        boolean answerWasScripted,
        String topBefore,
        String topAfter,
        Double confidenceBefore,
        Double confidenceAfter,
        double marginBefore,
        double marginAfter
) {

    public ClarificationRound {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** The strongest-candidate ranking moved — the most decisive kind of usefulness. */
    public boolean changedTopCandidate() {
        return topBefore != null && topAfter != null && !topBefore.equals(topAfter);
    }

    /** The two leading categories separated — ambiguity genuinely decreased. */
    public boolean increasedMargin() {
        return marginAfter > marginBefore + 1e-9;
    }

    public boolean increasedConfidence() {
        return confidenceBefore != null && confidenceAfter != null
                && confidenceAfter > confidenceBefore + 1e-9;
    }

    /**
     * Did this question earn its place? Any of the three signals counts: a question that
     * confirmed the leading candidate and pushed the margin out did real work even though the
     * ranking did not move.
     *
     * <p>Deliberately not "the final answer became correct" — that conflates the question's
     * value with the model's accuracy, and would score a good question as useless whenever the
     * model was going to be wrong anyway.
     */
    public boolean wasUseful() {
        return changedTopCandidate() || increasedMargin() || increasedConfidence();
    }

    /** Whether the customer was offered a way out of guessing (roadmap §11). */
    public boolean offeredNotSure() {
        return options.stream().anyMatch(option -> {
            String normalized = option.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("לא בטוח") || normalized.contains("לא יודע")
                    || normalized.contains("not sure") || normalized.contains("unsure");
        });
    }
}
