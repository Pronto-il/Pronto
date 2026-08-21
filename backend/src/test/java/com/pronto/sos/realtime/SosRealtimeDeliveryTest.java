package com.pronto.sos.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The outbound edge: correct addressing, and failures that stay put. */
class SosRealtimeDeliveryTest {

    private SimpMessagingTemplate messagingTemplate;
    private SosRealtimeDelivery delivery;

    @BeforeEach
    void setUp() {
        messagingTemplate = Mockito.mock(SimpMessagingTemplate.class);
        delivery = new SosRealtimeDelivery(messagingTemplate);
    }

    private static SosRealtimeMessage message() {
        return new SosRealtimeMessage(1L, SosRealtimeEventType.SOS_CREATED, 100L, Instant.now(), Map.of());
    }

    /**
     * The user id must be stringified to match {@code StompPrincipal.getName()} — that equality is
     * what makes per-user routing work, so it is asserted rather than assumed.
     */
    @Test
    void deliversToTheUserQueueKeyedByStringifiedUserId() {
        SosRealtimeMessage message = message();

        delivery.sendToUser(42L, message);

        verify(messagingTemplate).convertAndSendToUser("42", "/queue/sos", message);
    }

    /**
     * A broker fault must not escape: by this point the business transaction has committed, and a
     * failed push cannot be allowed to surface as an error on an already-successful action.
     */
    @Test
    void aBrokerFailureIsSwallowed() {
        Mockito.doThrow(new IllegalStateException("broker down"))
                .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> delivery.sendToUser(42L, message())).doesNotThrowAnyException();
    }

    /**
     * Isolation is per recipient, not per event: one dead session must not deprive the other
     * parties of their message.
     */
    @Test
    void oneFailedRecipientDoesNotPreventTheNext() {
        Mockito.doThrow(new IllegalStateException("gone"))
                .when(messagingTemplate).convertAndSendToUser(eq("1"), anyString(), any(Object.class));

        delivery.sendToUser(1L, message());
        delivery.sendToUser(2L, message());

        verify(messagingTemplate).convertAndSendToUser(eq("2"), eq("/queue/sos"), any(Object.class));
    }

    /** A null recipient (a professional row that vanished) is a no-op, not an NPE. */
    @Test
    void aNullRecipientIsIgnored() {
        delivery.sendToUser(null, message());

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
    }
}
