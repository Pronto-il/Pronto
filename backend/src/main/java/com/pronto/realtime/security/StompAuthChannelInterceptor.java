package com.pronto.realtime.security;

import com.pronto.auth.security.JwtPrincipalResolver;
import com.pronto.common.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The entire authentication and authorization surface of the WebSocket layer.
 *
 * <p>Runs on the client-inbound channel, so every frame a client sends passes through here before
 * the broker sees it. Three rules, and nothing else is permitted:
 *
 * <ol>
 *   <li><b>{@code CONNECT} must carry a valid JWT.</b> Read from the STOMP {@code Authorization}
 *       native header and resolved by {@link JwtPrincipalResolver} — the exact same component the
 *       HTTP filter uses, so signature checking, expiry and the deleted-user revocation rule are
 *       shared, not reimplemented. A missing, malformed, expired or revoked token throws, which
 *       Spring turns into a STOMP {@code ERROR} frame and a closed session. There is no
 *       anonymous mode.</li>
 *   <li><b>{@code SUBSCRIBE} is allow-listed to one destination</b>, {@value #SOS_USER_DESTINATION}.
 *       Anything else is refused outright rather than quietly delivering nothing, so a client
 *       probing {@code /queue/sos} or {@code /topic/**} gets an error instead of silence.</li>
 *   <li><b>{@code SEND} is refused unconditionally.</b> WebSocket is a delivery mechanism in this
 *       system, not a second command API — every business action stays on REST, behind the
 *       service layer and the SOS state machine. Refusing {@code SEND} here is the enforcement
 *       of that, alongside {@code WebSocketConfig} declining to register any application
 *       destination prefix at all (so there is no {@code @MessageMapping} for a frame to reach
 *       even if one slipped through).</li>
 * </ol>
 *
 * <p><b>Why the identity cannot be spoofed.</b> The principal is built solely from verified JWT
 * claims and attached to the session at {@code CONNECT}. Client-supplied user/customer/
 * professional ids are never read, anywhere. And because outbound routing uses
 * {@code convertAndSendToUser} keyed on {@link StompPrincipal#getName()}, subscribing to
 * {@code /user/queue/sos} resolves to <em>this session's own</em> queue — the {@code /user}
 * prefix is rewritten server-side per session, so there is no destination string a client can
 * craft to reach somebody else's messages.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    /** The one destination any client may subscribe to. */
    public static final String SOS_USER_DESTINATION = "/user/queue/sos";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final JwtPrincipalResolver jwtPrincipalResolver;

    public StompAuthChannelInterceptor(JwtPrincipalResolver jwtPrincipalResolver) {
        this.jwtPrincipalResolver = jwtPrincipalResolver;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscription(accessor);
            case SEND -> throw refuse("This WebSocket delivers events only; use the REST API for actions.");
            default -> {
                // DISCONNECT / UNSUBSCRIBE / ACK / NACK: harmless session bookkeeping on a
                // session that already passed CONNECT. Nothing to check.
            }
        }
        return message;
    }

    /** Rule 1 — a session has an identity from its first frame, or it has no session. */
    private void authenticate(StompHeaderAccessor accessor) {
        String token = JwtPrincipalResolver.stripBearer(accessor.getFirstNativeHeader(AUTHORIZATION_HEADER));
        Optional<AuthenticatedUser> principal = jwtPrincipalResolver.resolve(token);
        if (principal.isEmpty()) {
            log.debug("realtime.connect.rejected reason=missing-or-invalid-token");
            throw refuse("A valid Authorization token is required to open a realtime connection.");
        }
        AuthenticatedUser user = principal.get();
        accessor.setUser(new StompPrincipal(user.id(), user.role()));
        log.debug("realtime.connect.accepted userId={}", user.id());
    }

    /** Rule 2 — authenticated, and only to the one destination that exists. */
    private void authorizeSubscription(StompHeaderAccessor accessor) {
        StompPrincipal principal = currentPrincipal(accessor);
        if (principal == null) {
            throw refuse("Not authenticated.");
        }
        String destination = accessor.getDestination();
        if (!SOS_USER_DESTINATION.equals(destination)) {
            log.warn("realtime.subscribe.rejected userId={} destination={}", principal.userId(), destination);
            throw refuse("Subscription to " + destination + " is not permitted.");
        }
    }

    private static StompPrincipal currentPrincipal(StompHeaderAccessor accessor) {
        return accessor.getUser() instanceof StompPrincipal stompPrincipal ? stompPrincipal : null;
    }

    /**
     * Thrown back through the inbound channel; Spring's STOMP support converts it into an
     * {@code ERROR} frame to the client and tears the session down.
     */
    private static MessageDeliveryException refuse(String reason) {
        return new MessageDeliveryException(reason);
    }
}
