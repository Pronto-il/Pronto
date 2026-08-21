package com.pronto.issues.event;

/**
 * Published by {@code IssuesService.create} once an issue and its clarification history are
 * committed, to kick off Professional Brief generation.
 *
 * <p>Carries only the id: the listener runs in its own transaction, after commit, and reloads
 * everything it needs. Passing detached entities across an async boundary is how stale reads
 * and lazy-loading failures happen.
 */
public record IssueCreatedEvent(Long issueId) {
}
