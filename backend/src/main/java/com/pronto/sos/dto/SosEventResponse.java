package com.pronto.sos.dto;

import com.pronto.sos.entity.SosActorType;
import com.pronto.sos.entity.SosEventType;
import com.pronto.sos.entity.SosRequestStatus;

import java.time.Instant;

/**
 * One entry in {@code GET /api/sos/requests/{id}/events}.
 *
 * <p>This shape is also what the realtime layer will push in the next phase, which is why it
 * carries {@code toStatus} and {@code detail} inline: a client that receives one of these live
 * should be able to append it to the timeline it is already rendering without re-fetching
 * anything.
 */
public record SosEventResponse(
        Long id,
        SosEventType eventType,
        SosActorType actorType,
        Long professionalId,
        Long sosOfferId,
        SosRequestStatus fromStatus,
        SosRequestStatus toStatus,
        String detail,
        Instant createdAt
) {
}
