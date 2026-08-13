package com.pronto.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code notifications} table. {@code userId}/{@code relatedOrderId} are
 * plain FK columns (not {@code @ManyToOne} associations), matching the convention already
 * used by {@code bookings.entity.Order}/{@code issues.entity.Issue}. Mapping matches the
 * already-applied {@code V9__create_notifications.sql}, plus this milestone's {@code V14}
 * migration, exactly ({@code ddl-auto: validate}). See
 * {@code docs/architecture/data-model.md} §2.10 and
 * {@code docs/architecture/api-contract-notifications.md} §1/§4.3.
 *
 * <p>No {@code @PrePersist}/{@code @PreUpdate} auditing callbacks (unlike {@code Order}/
 * {@code Issue}) — {@code created_at}/{@code sent_at}/{@code read_at} are each set explicitly
 * by the service layer at the specific moment they become meaningful (insert time, dispatch
 * time, read time respectively), not implicitly on every save.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "related_order_id")
    private Long relatedOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 50)
    private NotificationMessageType messageType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 20)
    private NotificationDeliveryStatus deliveryStatus;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
        // JPA
    }

    /**
     * §4.3 of the contract doc: the in-app row is {@code SENT}/{@code sentAt = now()}
     * immediately (delivery is the row itself being queryable); the email row starts
     * {@code PENDING}/{@code sentAt = null}, dispatched later by
     * {@code notifications.scheduler.EmailDispatchJob}.
     */
    public Notification(Long userId, Long relatedOrderId, NotificationMessageType messageType,
                         NotificationChannel channel, NotificationDeliveryStatus deliveryStatus, Instant sentAt) {
        this.userId = userId;
        this.relatedOrderId = relatedOrderId;
        this.messageType = messageType;
        this.channel = channel;
        this.deliveryStatus = deliveryStatus;
        this.sentAt = sentAt;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getRelatedOrderId() {
        return relatedOrderId;
    }

    public NotificationMessageType getMessageType() {
        return messageType;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public NotificationDeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(NotificationDeliveryStatus deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
