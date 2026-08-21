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

    /**
     * <b>No longer written by any code path.</b> It used to be: an order timing out expired its
     * issue along with it, which stranded everything the customer had already provided. That is
     * now {@code IssueRepository.reopenIfBooked} — an expired order returns its issue to
     * {@link #OPEN} so another professional can be booked for the same problem.
     *
     * <p>Kept because this enum mirrors {@code issues.status}'s {@code CHECK} constraint
     * ({@code V6}) and rows written before that change may still hold it, so the mapping must
     * still be able to read one back. Dropping the value would turn an old row into a startup
     * failure for no gain.
     */
    EXPIRED
}
