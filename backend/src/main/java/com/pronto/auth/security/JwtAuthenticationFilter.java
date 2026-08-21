package com.pronto.auth.security;

import com.pronto.common.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Validates {@code Authorization: Bearer <jwt>} on every request, per
 * {@code docs/architecture/api-contract.md} §3.1.
 *
 * <p>On a missing or invalid (malformed/expired/bad-signature) token, this filter simply
 * leaves the security context empty and lets the request continue —
 * {@code SecurityConfig}'s {@code authorizeHttpRequests} rule for the target endpoint then
 * either allows it (public endpoint) or rejects it with {@code 401 UNAUTHORIZED} via
 * {@link JsonAuthenticationEntryPoint}.
 *
 * <p>The token-to-identity decision itself (including §3.1's per-request revocation check, which
 * treats a valid signature over a deleted user as unauthenticated) now lives in
 * {@link JwtPrincipalResolver}, extracted when the realtime layer needed the identical answer for
 * a STOMP {@code CONNECT} frame. This filter's behaviour is unchanged; it simply no longer owns
 * that logic alone. See that class for why sharing it matters.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtPrincipalResolver jwtPrincipalResolver;

    public JwtAuthenticationFilter(JwtPrincipalResolver jwtPrincipalResolver) {
        this.jwtPrincipalResolver = jwtPrincipalResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String token = JwtPrincipalResolver.stripBearer(request.getHeader("Authorization"));
        if (token != null) {
            Optional<AuthenticatedUser> principal = jwtPrincipalResolver.resolve(token);
            if (principal.isPresent()) {
                AuthenticatedUser user = principal.get();
                List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role()));
                var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                log.debug("Rejecting invalid or revoked JWT on {}", request.getRequestURI());
            }
        }
        filterChain.doFilter(request, response);
    }
}
