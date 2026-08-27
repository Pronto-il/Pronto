package com.pronto.notifications.scheduler;

import com.pronto.auth.email.EmailSender;
import com.pronto.notifications.entity.Notification;
import com.pronto.notifications.entity.NotificationChannel;
import com.pronto.notifications.entity.NotificationDeliveryStatus;
import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.repository.NotificationRepository;
import com.pronto.users.entity.User;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What actually leaves the building.
 *
 * <p>The reported incident was an email reading {@code "Pronto — Order #null: status changed to
 * SOS_NO_PROFESSIONALS"}. {@code NotificationServiceImpl} no longer writes an {@code EMAIL} row
 * for that type at all, so the first test here is about the rows that were written <em>before</em>
 * that fix and are still sitting in the {@code PENDING} queue — the ones this job would have
 * delivered on its first poll after deploy.
 */
class EmailDispatchJobTest {

    private static final String RECIPIENT = "customer@example.com";

    private NotificationRepository notificationRepository;
    private UserRepository userRepository;
    private EmailSender emailSender;
    private EmailDispatchJob job;

    @BeforeEach
    void setUp() {
        notificationRepository = Mockito.mock(NotificationRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        emailSender = Mockito.mock(EmailSender.class);
        job = new EmailDispatchJob(notificationRepository, userRepository, emailSender);

        User recipient = Mockito.mock(User.class);
        when(recipient.getEmail()).thenReturn(RECIPIENT);
        when(userRepository.findById(any())).thenReturn(Optional.of(recipient));
    }

    private void queue(Notification... rows) {
        when(notificationRepository.findFirst50ByChannelAndDeliveryStatusOrderByCreatedAtAsc(
                NotificationChannel.EMAIL, NotificationDeliveryStatus.PENDING)).thenReturn(List.of(rows));
    }

    private static Notification sosEmailRow(NotificationMessageType type) {
        return Notification.forSosRequest(1L, 77L, type, NotificationChannel.EMAIL,
                NotificationDeliveryStatus.PENDING, null);
    }

    private static Notification orderEmailRow(NotificationMessageType type, Long orderId) {
        return new Notification(1L, orderId, type, NotificationChannel.EMAIL,
                NotificationDeliveryStatus.PENDING, null);
    }

    @Test
    void aLegacySosNoProfessionalsRowIsNeverDelivered() {
        Notification row = sosEmailRow(NotificationMessageType.SOS_NO_PROFESSIONALS);
        queue(row);

        job.dispatchPendingEmails();

        verifyNoInteractions(emailSender);
        assertThat(row.getDeliveryStatus()).isEqualTo(NotificationDeliveryStatus.SUPPRESSED);
        assertThat(row.getSentAt()).isNull();
        verify(notificationRepository).save(row);
        // Not even the recipient's address is looked up -- there is nothing to send it.
        verify(userRepository, never()).findById(any());
    }

    @Test
    void aLegacyRoutingFailureRowIsNeverDeliveredEither() {
        Notification row = sosEmailRow(NotificationMessageType.SOS_TEMPORARILY_UNAVAILABLE);
        queue(row);

        job.dispatchPendingEmails();

        verifyNoInteractions(emailSender);
        assertThat(row.getDeliveryStatus()).isEqualTo(NotificationDeliveryStatus.SUPPRESSED);
    }

    /** A suppressed row must not take the rest of the batch down with it. */
    @Test
    void aSuppressedRowDoesNotStopTheOnesBehindIt() {
        Notification suppressed = sosEmailRow(NotificationMessageType.SOS_NO_PROFESSIONALS);
        Notification real = orderEmailRow(NotificationMessageType.ORDER_CONFIRMED, 12L);
        queue(suppressed, real);

        job.dispatchPendingEmails();

        verify(emailSender).sendOrderStatusEmail(anyString(), anyString(), anyString());
        assertThat(suppressed.getDeliveryStatus()).isEqualTo(NotificationDeliveryStatus.SUPPRESSED);
        assertThat(real.getDeliveryStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
    }

    @Test
    void aLegitimateOrderTransitionStillSends_withoutQuotingItsOwnEnum() {
        queue(orderEmailRow(NotificationMessageType.ORDER_CONFIRMED, 12L));

        job.dispatchPendingEmails();

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendOrderStatusEmail(org.mockito.ArgumentMatchers.eq(RECIPIENT),
                subject.capture(), body.capture());

        assertThat(subject.getValue()).doesNotContain("ORDER_CONFIRMED").doesNotContain("status changed to");
        assertThat(body.getValue()).doesNotContain("ORDER_CONFIRMED").contains("מספר הזמנה: 12");
    }

    /**
     * The {@code #null} itself. An SOS row has no order id by design, and the job used to render
     * one anyway.
     */
    @Test
    void anSosEmailNeverRendersAnOrderNumber() {
        queue(sosEmailRow(NotificationMessageType.SOS_ON_THE_WAY));

        job.dispatchPendingEmails();

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendOrderStatusEmail(anyString(), subject.capture(), body.capture());

        assertThat(subject.getValue()).doesNotContain("null").doesNotContain("Order #");
        assertThat(body.getValue()).doesNotContain("null").doesNotContain("Order #")
                .doesNotContain("מספר הזמנה");
    }

    @Test
    void aSendFailureMarksOnlyThatRowFailed() {
        Notification row = orderEmailRow(NotificationMessageType.ORDER_COMPLETED, 12L);
        queue(row);
        Mockito.doThrow(new IllegalStateException("SES is having a moment"))
                .when(emailSender).sendOrderStatusEmail(anyString(), anyString(), anyString());

        job.dispatchPendingEmails();

        // FAILED, not SUPPRESSED: the two must stay distinguishable, because FAILED is the signal
        // an operator uses to find genuine bugs.
        assertThat(row.getDeliveryStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
    }
}
