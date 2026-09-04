package com.pronto.ai.taxonomy;

import java.util.Locale;
import java.util.Optional;

/**
 * What the customer wants done — orthogonal to which trade does it.
 *
 * <p>A controlled enum rather than free text because it is read by code: the same profession
 * and subcategory behave differently when the customer is reporting a failure than when they
 * are planning work, and a value that can be spelled three ways cannot drive a decision.
 *
 * <p><b>{@link #EMERGENCY} is about the situation, not the trade.</b> A burst pipe is an
 * emergency; installing a tap next month is not, and both are plumbing. Nothing here should be
 * inferred from the profession alone.
 */
public enum Intent {

    /** Something worked and now does not. The default reading of a reported symptom. */
    REPAIR,

    /** Something new is to be fitted, or an existing item replaced with a new one. */
    INSTALLATION,

    /** Routine servicing or cleaning of something that is not currently broken. */
    MAINTENANCE,

    /** Planned, larger, usually multi-visit work — a renovation rather than a fault. */
    PROJECT,

    /**
     * The customer wants to know what is wrong, and finding out is itself the job. Leak
     * detection is the archetype: the deliverable is the answer, not the repair.
     */
    DIAGNOSIS,

    /**
     * Active damage, or a safety risk, that makes waiting unacceptable — flooding, a suspected
     * gas leak, being locked out. Rare by construction: see {@link Urgency#CRITICAL}.
     */
    EMERGENCY;

    /** Case-insensitive lookup; empty rather than throwing for an unrecognised value. */
    public static Optional<Intent> parse(String value) {
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
