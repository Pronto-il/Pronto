package com.pronto.sos.realtime;

import com.pronto.realtime.security.StompPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * The single place a realtime message actually leaves the application, and the inner half of the
 * failure-isolation guarantee.
 *
 * <p><b>Send failures are swallowed, deliberately.</b> By the time anything here runs, the
 * business transaction has already committed — the professional really is selected, the job
 * really is complete. A broker hiccup, a half-closed socket or a serialization fault must not be
 * allowed to turn a successful, already-durable outcome into an error the caller sees. Isolation
 * is per-recipient rather than per-event, so one dead session cannot deprive the other parties of
 * their message: each send is attempted independently.
 *
 * <p>What a dropped message costs is bounded and already designed for: the client refetches
 * canonical state over REST on reconnect, and {@code sos_events} retains the full history
 * regardless. Realtime is an accelerator here, never the record.
 *
 * <p>Existing as a named component (rather than the publisher calling
 * {@link SimpMessagingTemplate} directly) is also what keeps the swap to a real broker relay a
 * one-class change, and what lets the routing tests assert on delivery without a running broker.
 */
@Component
public class SosRealtimeDelivery {

    private static final Logger log = LoggerFactory.getLogger(SosRealtimeDelivery.class);

    /**
     * The client-visible destination. A client subscribes to {@code /user/queue/sos}; Spring
     * rewrites that per session, so this value is the un-prefixed half.
     */
    public static final String SOS_QUEUE = "/queue/sos";

    private final SimpMessagingTemplate messagingTemplate;

    public SosRealtimeDelivery(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Delivers to every session authenticated as {@code recipientUserId}, and to no one else.
     *
     * <p>The user id is stringified to match {@link StompPrincipal#getName()} — that equality is
     * what Spring's user-destination resolution keys on, and therefore what makes cross-user
     * delivery impossible rather than merely unlikely.
     *
     * @param recipientUserId a {@code users.id}, always derived server-side from committed state
     */
    public void sendToUser(Long recipientUserId, SosRealtimeMessage message) {
        if (recipientUserId == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(recipientUserId), SOS_QUEUE, message);
        } catch (RuntimeException e) {
            // Never rethrown: see this class's Javadoc.
            log.warn("sos.realtime.delivery-failed userId={} eventType={} sosRequestId={} reason={}",
                    recipientUserId, message.eventType(), message.sosRequestId(), e.toString());
        }
    }
}
