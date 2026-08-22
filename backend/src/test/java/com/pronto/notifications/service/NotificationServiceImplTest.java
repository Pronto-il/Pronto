package com.pronto.notifications.service;

import com.pronto.notifications.dto.NotificationResponse;
import com.pronto.notifications.entity.Notification;
import com.pronto.notifications.entity.NotificationChannel;
import com.pronto.notifications.entity.NotificationDeliveryStatus;
import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The feed's deep-link contract. Every test here is about the same question: <b>can the reader
 * actually get to the thing they were told about?</b>
 *
 * <p>The bug this file pins is a whole class of notification being a dead end. SOS rows carry
 * {@code related_sos_request_id}, but the customer's live SOS screen is keyed by <em>issue</em>
 * ({@code /issues/{id}/sos-booking}), so the bell had a subject it could not turn into a
 * destination and every customer-facing SOS row did nothing when tapped. Deriving the issue id at
 * read time closes that without copying SOS state into this package.
 */
class NotificationServiceImplTest {

    private static final Long CALLER_ID = 1L;

    private NotificationRepository notificationRepository;
    private SosRequestIssueResolver sosRequestIssueResolver;
    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        notificationRepository = Mockito.mock(NotificationRepository.class);
        sosRequestIssueResolver = Mockito.mock(SosRequestIssueResolver.class);
        service = new NotificationServiceImpl(notificationRepository, sosRequestIssueResolver);
        when(sosRequestIssueResolver.issueIdsBySosRequestId(any())).thenReturn(Map.of());
    }

    private static Notification sosRow(Long id, Long sosRequestId, NotificationMessageType type) {
        Notification notification = Notification.forSosRequest(CALLER_ID, sosRequestId, type,
                NotificationChannel.IN_APP, NotificationDeliveryStatus.SENT, Instant.now());
        setField(notification, "id", id);
        return notification;
    }

    private static Notification orderRow(Long id, Long orderId) {
        Notification notification = new Notification(CALLER_ID, orderId, NotificationMessageType.ORDER_CONFIRMED,
                NotificationChannel.IN_APP, NotificationDeliveryStatus.SENT, Instant.now());
        setField(notification, "id", id);
        return notification;
    }

    private void stubFeed(Notification... rows) {
        when(notificationRepository.findByUserIdAndChannelOrderByCreatedAtDesc(CALLER_ID, NotificationChannel.IN_APP))
                .thenReturn(List.of(rows));
    }

    @Test
    void anSosRowCarriesTheIssueItsRequestWasActivatedOn() {
        stubFeed(sosRow(1L, 77L, NotificationMessageType.SOS_CANDIDATES_READY));
        when(sosRequestIssueResolver.issueIdsBySosRequestId(any())).thenReturn(Map.of(77L, 42L));

        NotificationResponse row = service.getFeed(CALLER_ID, false).notifications().getFirst();

        assertThat(row.relatedSosRequestId()).isEqualTo(77L);
        assertThat(row.relatedIssueId()).isEqualTo(42L);
        assertThat(row.relatedOrderId()).isNull();
    }

    /** Order notifications are untouched by this — they navigate by order id and always did. */
    @Test
    void anOrderRowIsUnaffectedAndCostsNoSosLookup() {
        stubFeed(orderRow(1L, 500L));

        NotificationResponse row = service.getFeed(CALLER_ID, false).notifications().getFirst();

        assertThat(row.relatedOrderId()).isEqualTo(500L);
        assertThat(row.relatedSosRequestId()).isNull();
        assertThat(row.relatedIssueId()).isNull();
        verify(sosRequestIssueResolver, never()).issueIdsBySosRequestId(any());
    }

    /** One batched lookup for the whole feed, not one per row — the feed is unpaginated. */
    @Test
    void everySosRowInAFeedIsResolvedInASingleLookup() {
        stubFeed(sosRow(1L, 77L, NotificationMessageType.SOS_CANDIDATES_READY),
                sosRow(2L, 77L, NotificationMessageType.SOS_ON_THE_WAY),
                sosRow(3L, 78L, NotificationMessageType.SOS_EXPIRED),
                orderRow(4L, 500L));
        when(sosRequestIssueResolver.issueIdsBySosRequestId(any())).thenReturn(Map.of(77L, 42L, 78L, 43L));

        List<NotificationResponse> rows = service.getFeed(CALLER_ID, false).notifications();

        assertThat(rows).extracting(NotificationResponse::relatedIssueId)
                .containsExactly(42L, 42L, 43L, null);
        verify(sosRequestIssueResolver).issueIdsBySosRequestId(eq(java.util.Set.of(77L, 78L)));
    }

    /**
     * An SOS request that no longer resolves leaves the row without a destination rather than
     * failing the feed. A bell that 500s because one deep-link hint could not be computed is a
     * far worse outcome than a row that does not navigate.
     */
    @Test
    void anUnresolvableSosRequestLeavesTheRowWithoutADestination() {
        stubFeed(sosRow(1L, 77L, NotificationMessageType.SOS_EXPIRED));

        assertThat(service.getFeed(CALLER_ID, false).notifications().getFirst().relatedIssueId()).isNull();
    }

    @Test
    void aFailingResolverDegradesToNoDeepLinkRatherThanBreakingTheFeed() {
        stubFeed(sosRow(1L, 77L, NotificationMessageType.SOS_EXPIRED));
        when(sosRequestIssueResolver.issueIdsBySosRequestId(any()))
                .thenThrow(new IllegalStateException("database is having a moment"));

        List<NotificationResponse> rows = service.getFeed(CALLER_ID, false).notifications();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().relatedIssueId()).isNull();
    }

    /** Marking one row read returns the same enriched shape the feed does. */
    @Test
    void markReadReturnsTheIssueIdToo() {
        Notification row = sosRow(1L, 77L, NotificationMessageType.SOS_PROFESSIONAL_CONFIRMED);
        when(notificationRepository.findById(1L)).thenReturn(java.util.Optional.of(row));
        when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sosRequestIssueResolver.issueIdsBySosRequestId(any())).thenReturn(Map.of(77L, 42L));

        NotificationResponse response = service.markRead(CALLER_ID, 1L);

        assertThat(response.relatedIssueId()).isEqualTo(42L);
        assertThat(response.readAt()).isNotNull();
    }

    @Test
    void markReadOnAnOrderRowSkipsTheSosLookupEntirely() {
        when(notificationRepository.findById(1L)).thenReturn(java.util.Optional.of(orderRow(1L, 500L)));
        when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.markRead(CALLER_ID, 1L).relatedIssueId()).isNull();
        verify(sosRequestIssueResolver, never()).issueIdsBySosRequestId(any());
    }

    @Test
    void unreadCountIsReportedRegardlessOfTheUnreadOnlyFilter() {
        stubFeed(orderRow(1L, 500L));
        when(notificationRepository.countByUserIdAndChannelAndReadAtIsNull(anyLong(), any())).thenReturn(4L);

        assertThat(service.getFeed(CALLER_ID, false).unreadCount()).isEqualTo(4L);
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            var field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
