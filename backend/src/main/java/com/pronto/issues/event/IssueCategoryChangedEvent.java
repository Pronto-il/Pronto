package com.pronto.issues.event;

/**
 * Published by {@code IssuesService.updateCategory} once a customer's category correction is
 * committed, to regenerate the Professional Brief against the new trade.
 *
 * <p>A separate event from {@link IssueCreatedEvent} rather than a re-publish of it: the issue was
 * not created, and a listener that cares about creation (a welcome notification, an analytics
 * counter) must not be told that it was. Both currently lead to the same work —
 * {@code IssueBriefService.generateFor} — because the brief is written *for a known trade* (the
 * prompt states the confirmed routing category outright), so a brief produced for plumbing is
 * actively misleading preparation material once the same issue is re-routed to electrical.
 *
 * <p>Carries only the id, for the same reason {@link IssueCreatedEvent} does.
 */
public record IssueCategoryChangedEvent(Long issueId) {
}
