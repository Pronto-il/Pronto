package com.pronto.sos.event;

import com.pronto.sos.entity.SosEventType;
import com.pronto.sos.entity.SosRequestStatus;

/**
 * Published by {@code sos.service.SosEventService} every time an {@code sos_events} row is
 * written.
 *
 * <p><b>This is the seam the realtime layer plugs into.</b> Nothing consumes it today — that is
 * intentional and is the point. The next phase adds a {@code @TransactionalEventListener} that
 * forwards these to WebSocket subscribers, and because every SOS transition already publishes
 * one, that listener is purely additive: no business logic moves, no service signature changes,
 * no transition needs revisiting. The alternative — reaching into the services later to add
 * push calls — is how business logic and transport get tangled together.
 *
 * <p>Carries ids and enums only, never entities, for the same reason
 * {@code issues.event.IssueCreatedEvent} does: a listener runs after commit, potentially on
 * another thread, and passing detached entities across that boundary is how stale reads and
 * lazy-loading failures happen. A consumer reloads what it needs.
 *
 * <p>{@code toStatus} is {@code null} for events that record something happening without the
 * request itself changing state — a professional accepting an offer while others are still
 * being waited on, for instance.
 */
public record SosDomainEvent(
        Long sosRequestId,
        Long sosEventId,
        SosEventType eventType,
        SosRequestStatus toStatus
) {
}
