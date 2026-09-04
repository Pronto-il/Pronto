package com.pronto.ai.taxonomy;

import java.util.Locale;
import java.util.Optional;

/**
 * How soon the described situation needs someone, judged from the situation itself.
 *
 * <p><b>Urgency is a property of the description, not of the profession.</b> "A pipe burst and
 * the kitchen is flooding" and "I'd like a new tap fitted next month" are both plumbing and
 * sit at opposite ends of this scale. Deriving urgency from the trade would make the field
 * a restatement of the category and therefore worthless.
 *
 * <p>Deliberately not wired to Pronto's existing {@code issues.urgency_type}
 * ({@code STANDARD}/{@code SOS}), which is a <em>customer's product choice</em> about how to
 * book. This is the classifier's reading of the text. Keeping them separate is what allows
 * "the model thought this was CRITICAL but the customer booked STANDARD" to be a question
 * anyone can ask.
 */
public enum Urgency {

    /** Explicitly deferred, or plainly discretionary — planned or cosmetic work. */
    LOW,

    /** The ordinary case: a real fault, no active damage, normal scheduling. The default. */
    NORMAL,

    /** Meaningful loss of use, or damage that will worsen if left — same-day work. */
    HIGH,

    /**
     * Danger to people or property right now: active flooding, a suspected gas leak, live
     * exposed wiring.
     *
     * <p><b>Reserved, and deliberately hard to reach.</b> A word like "urgent" in the text is
     * not enough on its own — customers write it routinely. If everything is critical, the
     * level carries no information and the genuinely dangerous cases stop standing out.
     */
    CRITICAL;

    /** Case-insensitive lookup; empty rather than throwing for an unrecognised value. */
    public static Optional<Urgency> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
