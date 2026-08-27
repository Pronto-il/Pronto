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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * See {@code docs/architecture/api-contract-notifications.md} §3/§4.1/§4.3.
 */
@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationRepository notificationRepository;
    private final SosRequestIssueResolver sosRequestIssueResolver;

    public NotificationServiceImpl(NotificationRepository notificationRepository,
                                    SosRequestIssueResolver sosRequestIssueResolver) {
        this.notificationRepository = notificationRepository;
        this.sosRequestIssueResolver = sosRequestIssueResolver;
    }

    /**
     * §4.3 — same transaction as the order transition that triggered it (this method carries no
     * {@code @Transactional} of its own; it runs inside the caller's — {@code BookingsService}'s
     * — existing transactional boundary, deliberately, per §4.3's "no outbox pattern, no event
     * bus" simplicity call).
     *
     * <p><b>The {@code IN_APP} row is unconditional; the {@code EMAIL} row is not.</b> §4.3's
     * original "two rows per event" made every status Pronto has an email nobody chose to send,
     * which is how a customer whose SOS search found nobody was mailed
     * {@code SOS_NO_PROFESSIONALS}. {@link NotificationEmailCopy} is the allowlist, and it is
     * consulted here rather than at dispatch time so a suppressed event leaves no row loitering
     * in the {@code PENDING} queue at all.
     */
    @Override
    public void recordOrderNotification(Long orderId, Long recipientUserId, NotificationMessageType messageType) {
        Instant now = Instant.now();
        notificationRepository.save(new Notification(recipientUserId, orderId, messageType,
                NotificationChannel.IN_APP, NotificationDeliveryStatus.SENT, now));
        if (NotificationEmailCopy.isEmailable(messageType)) {
            notificationRepository.save(new Notification(recipientUserId, orderId, messageType,
                    NotificationChannel.EMAIL, NotificationDeliveryStatus.PENDING, null));
        }
    }

    /** Pronto SOS. Structurally identical to {@link #recordOrderNotification} above. */
    @Override
    public void recordSosNotification(Long sosRequestId, Long recipientUserId, NotificationMessageType messageType) {
        Instant now = Instant.now();
        notificationRepository.save(Notification.forSosRequest(recipientUserId, sosRequestId, messageType,
                NotificationChannel.IN_APP, NotificationDeliveryStatus.SENT, now));
        if (NotificationEmailCopy.isEmailable(messageType)) {
            notificationRepository.save(Notification.forSosRequest(recipientUserId, sosRequestId, messageType,
                    NotificationChannel.EMAIL, NotificationDeliveryStatus.PENDING, null));
        }
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
        // One batched lookup for the whole (unpaginated) feed rather than a query per SOS row --
        // a customer with several SOS attempts behind them would otherwise turn one feed read
        // into N.
        Map<Long, Long> issueIds = resolveIssueIds(rows.stream()
                .map(Notification::getRelatedSosRequestId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        List<NotificationResponse> notifications = rows.stream()
                .map(row -> toResponse(row, issueIds))
                .toList();
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
        return toResponse(notification, resolveIssueIds(notification.getRelatedSosRequestId() == null
                ? Set.of()
                : Set.of(notification.getRelatedSosRequestId())));
    }

    /** §3.3. */
    @Override
    @Transactional
    public int markAllRead(Long callerId) {
        return notificationRepository.markAllRead(callerId, Instant.now());
    }

    private NotificationResponse toResponse(Notification notification, Map<Long, Long> issueIds) {
        return new NotificationResponse(notification.getId(), notification.getMessageType(),
                notification.getRelatedOrderId(), notification.getRelatedSosRequestId(),
                notification.getRelatedSosRequestId() == null
                        ? null
                        : issueIds.get(notification.getRelatedSosRequestId()),
                notification.getReadAt(), notification.getCreatedAt());
    }

    /**
     * The SOS-request-to-issue lookup a customer's deep link needs. Best effort by design: if the
     * resolver fails, the feed still renders and the affected rows simply have no destination —
     * a notification list that 500s because a deep-link hint could not be computed would be a far
     * worse outcome than a row that does not navigate.
     */
    private Map<Long, Long> resolveIssueIds(Set<Long> sosRequestIds) {
        if (sosRequestIds.isEmpty()) {
            return Map.of();
        }
        try {
            return sosRequestIssueResolver.issueIdsBySosRequestId(sosRequestIds);
        } catch (RuntimeException e) {
            log.warn("notifications.sos-issue-lookup-failed count={}", sosRequestIds.size(), e);
            return Map.of();
        }
    }
}
