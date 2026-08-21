package com.pronto.sos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code sos_events} table — the append-only chronological history of one
 * SOS request. Mapping matches {@code V34__create_sos.sql} exactly ({@code ddl-auto:
 * validate}).
 *
 * <p>Immutable by design: no setters, no {@code @PreUpdate}, and nothing in this package ever
 * updates or deletes a row. An audit trail that can be rewritten is not one.
 *
 * <p>Each row carries enough context ({@code professionalId}, {@code sosOfferId},
 * {@code fromStatus}/{@code toStatus}, {@code detail}) to be rendered without re-querying the
 * request. That is deliberate: the next phase publishes these rows over WebSockets, and a
 * payload that needs a follow-up query to be useful defeats the point.
 */
@Entity
@Table(name = "sos_events")
public class SosEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sos_request_id", nullable = false)
    private Long sosRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private SosEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private SosActorType actorType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "professional_id")
    private Long professionalId;

    @Column(name = "sos_offer_id")
    private Long sosOfferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 40)
    private SosRequestStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 40)
    private SosRequestStatus toStatus;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SosEvent() {
        // JPA
    }

    public SosEvent(Long sosRequestId, SosEventType eventType, SosActorType actorType, Long actorUserId,
                     Long professionalId, Long sosOfferId, SosRequestStatus fromStatus, SosRequestStatus toStatus,
                     String detail) {
        this.sosRequestId = sosRequestId;
        this.eventType = eventType;
        this.actorType = actorType;
        this.actorUserId = actorUserId;
        this.professionalId = professionalId;
        this.sosOfferId = sosOfferId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.detail = detail;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSosRequestId() {
        return sosRequestId;
    }

    public SosEventType getEventType() {
        return eventType;
    }

    public SosActorType getActorType() {
        return actorType;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public Long getSosOfferId() {
        return sosOfferId;
    }

    public SosRequestStatus getFromStatus() {
        return fromStatus;
    }

    public SosRequestStatus getToStatus() {
        return toStatus;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
