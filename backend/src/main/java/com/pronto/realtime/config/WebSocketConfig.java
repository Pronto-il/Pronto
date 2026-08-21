package com.pronto.realtime.config;

import com.pronto.realtime.security.StompAuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * STOMP-over-WebSocket transport for realtime delivery. Deliberately minimal — this class
 * configures a pipe, and holds no domain knowledge whatsoever (the SOS routing lives in
 * {@code sos.realtime}).
 *
 * <p><b>Handshake endpoint:</b> {@value #STOMP_ENDPOINT}, origin-restricted to the same
 * {@code pronto.cors.allowed-origins} list the REST CORS policy uses, so the browser allow-list
 * is configured once and cannot drift between the two transports.
 *
 * <p><b>No application destination prefix is registered, on purpose.</b> That is not an omission:
 * without one, there is no destination a client frame can be routed to, so no
 * {@code @MessageMapping} handler can exist in this application even by accident. Combined with
 * {@code StompAuthChannelInterceptor} refusing {@code SEND} outright, it makes "WebSocket must
 * not become a second business-command API" a structural property rather than a convention
 * someone has to remember. Every command stays on REST, behind the service layer and the SOS
 * state machine.
 *
 * <p><b>Simple in-memory broker</b> over {@code /queue}, with the standard {@code /user} prefix
 * for per-session destinations. No external broker is introduced — this matches the codebase's
 * single-instance, zero-external-dependency posture (same call as {@code EmailDispatchJob}'s
 * logging sender and the local storage mode). The consequence is stated plainly in
 * {@code realtime/README.md}: with more than one backend instance, a user connected to instance A
 * will not receive an event published on instance B, and a real broker relay becomes necessary.
 * Nothing about the SOS business layer changes when that day comes, because delivery is already
 * behind {@code SosRealtimeDelivery}.
 *
 * <p><b>SockJS is not enabled.</b> A native WebSocket plus a STOMP client covers every browser
 * this product targets, and the fallback transports would add a polling surface to secure for no
 * present benefit. It is one {@code .withSockJS()} away if that changes.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** The STOMP handshake path. Must be permitted at the HTTP layer — see {@code SecurityConfig}. */
    public static final String STOMP_ENDPOINT = "/ws";

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final List<String> allowedOrigins;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor,
                            @Value("${pronto.cors.allowed-origins}") List<String> allowedOrigins) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(STOMP_ENDPOINT)
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue");
        registry.setUserDestinationPrefix("/user");
        // No setApplicationDestinationPrefixes(...) -- see this class's Javadoc.
    }

    /**
     * The authentication/authorization gate. Registered on the <em>inbound</em> channel only:
     * outbound delivery needs no interception, because destinations are resolved server-side per
     * session and a client cannot influence what it is sent.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
