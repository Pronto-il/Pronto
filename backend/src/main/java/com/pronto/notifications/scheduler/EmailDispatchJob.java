package com.pronto.notifications.scheduler;

import com.pronto.auth.email.EmailSender;
import com.pronto.notifications.entity.Notification;
import com.pronto.notifications.entity.NotificationChannel;
import com.pronto.notifications.entity.NotificationDeliveryStatus;
import com.pronto.notifications.repository.NotificationRepository;
import com.pronto.notifications.service.NotificationEmailCopy;
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
 * 20s and dispatches each row via {@link EmailSender#sendOrderStatusEmail}.
 *
 * <p><b>This job composes no copy of its own.</b> It used to, by concatenating the row's
 * {@code messageType} and {@code relatedOrderId} into an English sentence — which sent
 * customers their own internal enum constants, and a literal {@code Order #null} for every SOS
 * row, since those carry an {@code sos_requests} id and no order id by design. Every word now
 * comes from {@link NotificationEmailCopy}, which is also the allowlist of what may be sent at
 * all.
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
        // The second lock on the same door. Since V53 NotificationServiceImpl does not create an
        // EMAIL row for a non-allowlisted type at all, so nothing reaching here should be
        // suppressible -- except the rows already sitting in this queue from before that change,
        // every one of which this job would otherwise deliver on its first poll after deploy.
        // Marked SUPPRESSED rather than skipped: they are the oldest rows in a batch ordered by
        // created_at, so leaving them PENDING would park them at the head of the queue forever.
        if (!NotificationEmailCopy.isEmailable(notification.getMessageType())) {
            log.info("notifications.email-suppressed id={} messageType={} — not customer-facing.",
                    notification.getId(), notification.getMessageType());
            notification.setDeliveryStatus(NotificationDeliveryStatus.SUPPRESSED);
            notificationRepository.save(notification);
            return;
        }
        try {
            User recipient = userRepository.findById(notification.getUserId()).orElse(null);
            if (recipient == null) {
                throw new IllegalStateException("Recipient user " + notification.getUserId() + " not found.");
            }
            NotificationEmailCopy.EmailMessage message = NotificationEmailCopy.render(notification);
            emailSender.sendOrderStatusEmail(recipient.getEmail(), message.subject(), message.body());
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
