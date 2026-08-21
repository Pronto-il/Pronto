package com.pronto.issues.entity;

/**
 * Lifecycle of an issue's Professional Brief. Explicit rather than inferred from null fields,
 * because generation happens asynchronously after the issue is created: the professional's
 * screen has to distinguish "not ready yet" from "we tried and could not", and neither state
 * may ever block a booking.
 */
public enum IssueBriefStatus {
    /** Row created with the issue; generation has not completed yet. */
    PENDING,
    /** Generated and stored. */
    READY,
    /** Generation was attempted and failed. Not retried automatically. */
    FAILED
}
