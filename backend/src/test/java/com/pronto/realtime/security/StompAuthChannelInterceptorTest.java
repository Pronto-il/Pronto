package com.pronto.realtime.security;

import com.pronto.auth.security.JwtPrincipalResolver;
import com.pronto.common.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The WebSocket security boundary. These tests are the reason the boundary is one small class:
 * every rule it enforces is asserted here directly, with no broker, no server and no Spring
 * context in the way.
 */
class StompAuthChannelInterceptorTest {

    private static final String VALID_TOKEN = "valid-token";
    private static final Long USER_ID = 42L;

    private JwtPrincipalResolver jwtPrincipalResolver;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtPrincipalResolver = Mockito.mock(JwtPrincipalResolver.class);
        interceptor = new StompAuthChannelInterceptor(jwtPrincipalResolver);
        when(jwtPrincipalResolver.resolve(any())).thenReturn(Optional.empty());
        when(jwtPrincipalResolver.resolve(VALID_TOKEN))
                .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "CUSTOMER")));
    }

    private Message<byte[]> frame(StompCommand command, String authorizationHeader, String destination,
                                   StompPrincipal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (principal != null) {
            accessor.setUser(principal);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static StompPrincipal authenticated() {
        return new StompPrincipal(USER_ID, "CUSTOMER");
    }

    // ------------------------------------------------------------------
    // 13. Unauthenticated connections are rejected
    // ------------------------------------------------------------------

    @Test
    void connectWithoutATokenIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null, null, null), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Authorization");
    }

    @Test
    void connectWithAnInvalidTokenIsRejected() {
        assertThatThrownBy(() ->
                interceptor.preSend(frame(StompCommand.CONNECT, "Bearer garbage", null, null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    /** A token for a deleted user resolves empty — revocation applies to sockets, not just HTTP. */
    @Test
    void connectWithARevokedTokenIsRejected() {
        when(jwtPrincipalResolver.resolve("revoked")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                interceptor.preSend(frame(StompCommand.CONNECT, "Bearer revoked", null, null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void connectWithoutTheBearerSchemeIsRejected() {
        assertThatThrownBy(() ->
                interceptor.preSend(frame(StompCommand.CONNECT, VALID_TOKEN, null, null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    // ------------------------------------------------------------------
    // 14. Identity comes from the token; cross-user access is blocked
    // ------------------------------------------------------------------

    /** The principal is built from verified claims — never from anything the client asserted. */
    @Test
    void aValidConnectAttachesThePrincipalDerivedFromTheToken() {
        Message<byte[]> message = frame(StompCommand.CONNECT, "Bearer " + VALID_TOKEN, null, null);

        interceptor.preSend(message, null);

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        assertThat(accessor.getUser()).isInstanceOf(StompPrincipal.class);
        StompPrincipal principal = (StompPrincipal) accessor.getUser();
        assertThat(principal.userId()).isEqualTo(USER_ID);
        // This equality is what Spring's user-destination routing keys on.
        assertThat(principal.getName()).isEqualTo("42");
    }

    @Test
    void subscribingToTheOwnUserQueueIsAllowed() {
        assertThatCode(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, null, "/user/queue/sos", authenticated()), null))
                .doesNotThrowAnyException();
    }

    @Test
    void subscribingWithoutAuthenticatingFirstIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, null, "/user/queue/sos", null), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Not authenticated");
    }

    /**
     * The headline cross-user case. Even though Spring's user-destination rewriting already means
     * a raw {@code /queue/sos} subscriber receives nothing, the attempt is refused outright rather
     * than silently delivering nothing — a probe should get an error, not ambiguity.
     */
    @Test
    void subscribingToAnotherUsersOrRawQueueIsRejected() {
        for (String destination : new String[]{
                "/queue/sos",
                "/user/43/queue/sos",
                "/topic/sos",
                "/user/queue/sos-other",
                "/queue/sos-user7",
                "/**"}) {
            assertThatThrownBy(() -> interceptor.preSend(
                    frame(StompCommand.SUBSCRIBE, null, destination, authenticated()), null))
                    .as("subscription to %s", destination)
                    .isInstanceOf(MessageDeliveryException.class);
        }
    }

    // ------------------------------------------------------------------
    // WebSocket is not a command API
    // ------------------------------------------------------------------

    /** Business commands stay on REST. A SEND frame has nowhere legitimate to go. */
    @Test
    void sendingAnythingIsRefusedEvenWhenAuthenticated() {
        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SEND, null, "/app/sos/select", authenticated()), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("REST");
    }

    @Test
    void disconnectAndUnsubscribeArePermittedBookkeeping() {
        assertThatCode(() -> {
            interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, null, null, authenticated()), null);
            interceptor.preSend(frame(StompCommand.DISCONNECT, null, null, authenticated()), null);
        }).doesNotThrowAnyException();
    }

    /** A non-STOMP message on the channel is passed through untouched rather than crashing. */
    @Test
    void aMessageWithNoStompCommandPassesThrough() {
        Message<byte[]> message = MessageBuilder.withPayload(new byte[0]).build();

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }
}
