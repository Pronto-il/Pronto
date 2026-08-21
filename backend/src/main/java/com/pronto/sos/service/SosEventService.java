package com.pronto.sos.service;

import com.pronto.sos.entity.SosActorType;
import com.pronto.sos.entity.SosEvent;
import com.pronto.sos.entity.SosEventType;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.event.SosDomainEvent;
import com.pronto.sos.repository.SosEventRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Writes the SOS history log, and publishes each entry as a Spring application event.
 *
 * <p><b>Why both.</b> The {@code sos_events} row is the durable record — it survives restarts,
 * backfills a timeline the customer opens an hour later, and is the audit trail. The published
 * {@link SosDomainEvent} is the live signal, and exists so that the realtime layer in the next
 * phase can be added as a {@code @TransactionalEventListener} that forwards to WebSocket
 * subscribers, <b>without a single line of business logic moving or changing</b>. That is the
 * whole reason this indirection is here now rather than being retrofitted later: every
 * transition already calls this method, so the publish point is already in the right place.
 *
 * <p>Deliberately carries no {@code @Transactional} of its own — it runs inside the caller's
 * transaction, so an event is written if and only if the transition that produced it commits.
 * The log therefore cannot claim something happened that was rolled back. Same rule, and same
 * rationale, as {@code NotificationServiceImpl#recordOrderNotification}.
 */
@Service
public class SosEventService {

    private final SosEventRepository sosEventRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SosEventService(SosEventRepository sosEventRepository, ApplicationEventPublisher eventPublisher) {
        this.sosEventRepository = sosEventRepository;
        this.eventPublisher = eventPublisher;
    }

    /** The full form. Every other overload delegates here. */
    public SosEvent record(Long sosRequestId, SosEventType eventType, SosActorType actorType, Long actorUserId,
                            Long professionalId, Long sosOfferId, SosRequestStatus fromStatus,
                            SosRequestStatus toStatus, String detail) {
        SosEvent event = sosEventRepository.save(new SosEvent(sosRequestId, eventType, actorType, actorUserId,
                professionalId, sosOfferId, fromStatus, toStatus, detail));
        eventPublisher.publishEvent(new SosDomainEvent(sosRequestId, event.getId(), eventType, toStatus));
        return event;
    }

    /** A system-driven status transition — matching, dispatch, expiry sweeps. */
    public SosEvent recordSystem(Long sosRequestId, SosEventType eventType, SosRequestStatus fromStatus,
                                   SosRequestStatus toStatus, String detail) {
        return record(sosRequestId, eventType, SosActorType.SYSTEM, null, null, null, fromStatus, toStatus, detail);
    }

    /** An action taken by the customer. */
    public SosEvent recordCustomer(Long sosRequestId, Long customerUserId, SosEventType eventType,
                                     SosRequestStatus fromStatus, SosRequestStatus toStatus, String detail) {
        return record(sosRequestId, eventType, SosActorType.CUSTOMER, customerUserId, null, null,
                fromStatus, toStatus, detail);
    }

    /** An action taken by a professional, always attributed to their offer. */
    public SosEvent recordProfessional(Long sosRequestId, Long actorUserId, Long professionalId, Long sosOfferId,
                                         SosEventType eventType, SosRequestStatus fromStatus,
                                         SosRequestStatus toStatus, String detail) {
        return record(sosRequestId, eventType, SosActorType.PROFESSIONAL, actorUserId, professionalId, sosOfferId,
                fromStatus, toStatus, detail);
    }

    /** The whole timeline for one request, oldest first. */
    public List<SosEvent> timeline(Long sosRequestId) {
        return sosEventRepository.findBySosRequestIdOrderByCreatedAtAscIdAsc(sosRequestId);
    }
}
