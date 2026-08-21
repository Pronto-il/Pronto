package com.pronto.notifications.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.notifications.dto.NotificationResponse;
import com.pronto.notifications.dto.NotificationsListResponse;
import com.pronto.notifications.entity.Notification;
import com.pronto.notifications.entity.NotificationChannel;
import com.pronto.notifications.entity.NotificationDeliveryStatus;
import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * See {@code docs/architecture/api-contract-notifications.md} §3/§4.1/§4.3.
 */
@Service
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationServiceImpl(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * §4.3 — two rows per event, same transaction as the order transition that triggered
     * them (this method carries no {@code @Transactional} of its own; it runs inside the
     * caller's — {@code BookingsService}'s — existing transactional boundary, deliberately,
     * per §4.3's "no outbox pattern, no event bus" simplicity call).
     */
    @Override
    public void recordOrderNotification(Long orderId, Long recipientUserId, NotificationMessageType messageType) {
        Instant now = Instant.now();
        Notification inApp = new Notification(recipientUserId, orderId, messageType,
                NotificationChannel.IN_APP, NotificationDeliveryStatus.SENT, now);
        Notification email = new Notification(recipientUserId, orderId, messageType,
                NotificationChannel.EMAIL, NotificationDeliveryStatus.PENDING, null);
        notificationRepository.save(inApp);
        notificationRepository.save(email);
    }

    /** Pronto SOS. Structurally identical to {@link #recordOrderNotification} above. */
    @Override
    public void recordSosNotification(Long sosRequestId, Long recipientUserId, NotificationMessageType messageType) {
        Instant now = Instant.now();
        Notification inApp = Notification.forSosRequest(recipientUserId, sosRequestId, messageType,
                NotificationChannel.IN_APP, NotificationDeliveryStatus.SENT, now);
        Notification email = Notification.forSosRequest(recipientUserId, sosRequestId, messageType,
                NotificationChannel.EMAIL, NotificationDeliveryStatus.PENDING, null);
        notificationRepository.save(inApp);
        notificationRepository.save(email);
    }

    /** §3.1. */
    @Override
    @Transactional(readOnly = true)
    public NotificationsListResponse getFeed(Long callerId, boolean unreadOnly) {
        List<Notification> rows = unreadOnly
                ? notificationRepository.findByUserIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(
                        callerId, NotificationChannel.IN_APP)
                : notificationRepository.findByUserIdAndChannelOrderByCreatedAtDesc(callerId, NotificationChannel.IN_APP);
        long unreadCount = notificationRepository.countByUserIdAndChannelAndReadAtIsNull(callerId, NotificationChannel.IN_APP);
        List<NotificationResponse> notifications = rows.stream().map(this::toResponse).toList();
        return new NotificationsListResponse(unreadCount, notifications);
    }

    /** §3.2. */
    @Override
    @Transactional
    public NotificationResponse markRead(Long callerId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Notification " + notificationId + " not found."));
        if (!notification.getUserId().equals(callerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You are not authorized to access this notification.");
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notification = notificationRepository.save(notification);
        }
        return toResponse(notification);
    }

    /** §3.3. */
    @Override
    @Transactional
    public int markAllRead(Long callerId) {
        return notificationRepository.markAllRead(callerId, Instant.now());
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getMessageType(),
                notification.getRelatedOrderId(), notification.getRelatedSosRequestId(),
                notification.getReadAt(), notification.getCreatedAt());
    }
}
