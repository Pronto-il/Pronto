package com.pronto.sos.entity;

/**
 * Mirrors {@code sos_requests.urgency}'s {@code CHECK} constraint ({@code V34}).
 *
 * <p>Two levels, not five: the field has to change behaviour to be worth storing, and the
 * only behaviour it drives today is the size of the candidate pool
 * ({@code sos.config.SosProperties#getEmergencyCandidatePoolSize}). An {@link #EMERGENCY} is
 * dispatched to a wider pool, accepting more professional interruption in exchange for a
 * better chance somebody answers immediately.
 */
public enum SosUrgency {

    /** The default. Something is broken and cannot wait for a scheduled booking. */
    URGENT,

    /** Active damage or a safety risk — flooding, no power, locked out at night. */
    EMERGENCY
}
