package com.pronto.issues.entity;

/**
 * Mirrors {@code issues.urgency_type}'s {@code CHECK (urgency_type IN ('STANDARD', 'SOS'))}
 * constraint (see {@code docs/architecture/data-model.md} §2.6).
 */
public enum IssueUrgencyType {
    STANDARD,
    SOS
}
