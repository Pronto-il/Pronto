package com.pronto.notifications.scheduler;

import com.pronto.auth.email.EmailSender;
import com.pronto.notifications.entity.Notification;
import com.pronto.notifications.entity.NotificationChannel;
import com.pronto.notifications.entity.NotificationDeliveryStatus;
import com.pronto.notifications.repository.NotificationRepository;
import com.pronto.users.entity.User;
import com.pronto.users.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * §4.4 of {@code docs/architecture/api-contract-notifications.md}. Polls the {@code EMAIL}-
 * channel {@code PENDING} queue (batch of 50, {@code idx_notifications_channel_status}) every
 * 20s and dispatches each row via {@link EmailSender#sendOrderStatusEmail}. Exact notification
 * copy is explicitly not decided this milestone (§7) — a minimal, generic English placeholder
 * is used.
 *
 * <p>No atomic per-row "claim" step before sending — see §4.4's "multi-instance race,
 * flagged not silently ignored" paragraph. Accepted MVP gap: this app is not currently
 * expected to run more than one instance.
 */
@Component
public class EmailDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchJob.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmailSender emailSender;

    public EmailDispatchJob(NotificationRepository notificationRepository, UserRepository userRepository,
                             EmailSender emailSender) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.emailSender = emailSender;
    }

    @Scheduled(fixedDelay = 20_000)
    @Transactional
    public void dispatchPendingEmails() {
        List<Notification> pending = notificationRepository.findFirst50ByChannelAndDeliveryStatusOrderByCreatedAtAsc(
                NotificationChannel.EMAIL, NotificationDeliveryStatus.PENDING);
        for (Notification notification : pending) {
            dispatchOne(notification);
        }
    }

    private void dispatchOne(Notification notification) {
        try {
            User recipient = userRepository.findById(notification.getUserId()).orElse(null);
            if (recipient == null) {
                throw new IllegalStateException("Recipient user " + notification.getUserId() + " not found.");
            }
            String subject = "Pronto — Order #" + notification.getRelatedOrderId();
            String body = "Pronto — Order #" + notification.getRelatedOrderId() + ": status changed to "
                    + notification.getMessageType();
            emailSender.sendOrderStatusEmail(recipient.getEmail(), subject, body);
            notification.setDeliveryStatus(NotificationDeliveryStatus.SENT);
            notification.setSentAt(Instant.now());
            notificationRepository.save(notification);
        } catch (Exception e) {
            log.warn("Failed to dispatch email notification {} (order {}): {}",
                    notification.getId(), notification.getRelatedOrderId(), e.getMessage(), e);
            notification.setDeliveryStatus(NotificationDeliveryStatus.FAILED);
            notificationRepository.save(notification);
        }
    }
}
