package com.pronto.auth.security;

import com.pronto.common.security.AuthenticatedUser;
import com.pronto.users.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import com.pronto.users.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * "Given a raw JWT, who is the caller?" — the single implementation of that question, shared by
 * every transport.
 *
 * <p><b>Why this exists.</b> It was extracted from {@link JwtAuthenticationFilter} when the
 * realtime layer arrived, because a STOMP {@code CONNECT} frame needs exactly the same answer as
 * an HTTP request does, and a second copy of "parse claims, load the user, check the soft-delete
 * tombstone, build the principal" is precisely the kind of duplication that later drifts — the
 * dangerous direction being a revocation rule that gets tightened in one transport and not the
 * other. There is now one place where a token becomes an identity.
 *
 * <p>The per-request revocation check from {@code docs/architecture/api-contract.md} §3.1 lives
 * here: a token whose {@code sub} no longer resolves to an existing, non-soft-deleted
 * {@code users} row yields {@link Optional#empty()} even when the signature is still valid. That
 * is what gives account deletion effectively-immediate effect across HTTP <em>and</em> WebSocket
 * without a token blocklist.
 *
 * <p>Returns {@link Optional#empty()} rather than throwing for every rejection reason — missing,
 * malformed, expired, bad signature, unknown or deleted user. Callers decide what a failure
 * means for their transport: the HTTP filter leaves the context empty and lets
 * {@code SecurityConfig} produce the 401; the STOMP interceptor refuses the connection.
 */
@Component
public class JwtPrincipalResolver {

    private static final String CLAIM_ROLE = "role";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtPrincipalResolver(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    /**
     * @param token a raw JWT with no {@code Bearer } prefix (see {@link #stripBearer})
     * @return the authenticated caller, or empty for any rejection reason
     */
    public Optional<AuthenticatedUser> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = jwtService.parseClaims(token);
            Long userId = Long.valueOf(claims.getSubject());
            String role = claims.get(CLAIM_ROLE, String.class);
            return userRepository.findById(userId)
                    .filter(user -> user.getDeletedAt() == null)
                    .map(User::getId)
                    .map(id -> new AuthenticatedUser(id, role));
        } catch (JwtException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Unwraps an {@code Authorization: Bearer <jwt>} header value. Returns {@code null} for a
     * missing header or any other scheme, which {@link #resolve} then treats as unauthenticated.
     */
    public static String stripBearer(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorizationHeader.substring(BEARER_PREFIX.length());
    }
}
