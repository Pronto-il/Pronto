package com.pronto.issues.entity;

/**
 * Mirrors {@code issues.status}'s {@code CHECK} constraint (see
 * {@code docs/architecture/data-model.md} §2.6). Every issue starts {@code OPEN}
 * (column default, matches {@code V6__create_issues.sql}) — this milestone's
 * {@code POST /api/issues} is the only place a row is created, and it never sets any other
 * status; later milestones' booking flows own the remaining transitions.
 */
public enum IssueStatus {
    OPEN,
    BOOKED,
    COMPLETED,
    CANCELLED,
    EXPIRED
}
