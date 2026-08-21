package com.pronto.realtime.security;

import java.security.Principal;

/**
 * The authenticated identity attached to a STOMP session by
 * {@link StompAuthChannelInterceptor}, derived <b>only</b> from the verified JWT — never from
 * anything the client asserts about itself.
 *
 * <p><b>{@link #getName()} returns the user id as a string, and that is load-bearing.</b> Spring's
 * user-destination machinery keys {@code /user/**} routing off exactly this value: when the
 * server calls {@code convertAndSendToUser("42", "/queue/sos", ...)}, the message reaches only
 * the sessions whose principal name is {@code "42"}. Making the name the user id is therefore
 * what makes per-user delivery correct, and is the reason a caller can never address another
 * user's queue by guessing a destination string — the destination is rewritten server-side per
 * session, not honoured as sent.
 *
 * <p>{@code role} is carried for completeness and diagnostics; the SOS routing layer deliberately
 * does not branch on it. Audience is derived from the committed database state (who owns this
 * request, who was offered it, who responded), not from the claim a socket happens to hold — a
 * professional's socket receiving customer-shaped data would be a routing bug, and a role check
 * would only hide it.
 */
public record StompPrincipal(Long userId, String role) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
