package com.pronto.auth.security;

import com.pronto.common.security.AuthenticatedUser;
import com.pronto.users.entity.User;
import com.pronto.users.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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
 * <p>Also implements the per-request revocation check from §3.1: a token whose {@code sub}
 * no longer resolves to an existing, non-soft-deleted {@code users} row is treated as
 * unauthenticated even if the JWT signature itself is still valid — this is what gives
 * account deletion effectively-immediate effect without a token blocklist.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String CLAIM_ROLE = "role";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            try {
                Claims claims = jwtService.parseClaims(token);
                Long userId = Long.valueOf(claims.getSubject());
                Optional<User> userOpt = userRepository.findById(userId);
                if (userOpt.isPresent() && userOpt.get().getDeletedAt() == null) {
                    User user = userOpt.get();
                    String role = claims.get(CLAIM_ROLE, String.class);
                    AuthenticatedUser principal = new AuthenticatedUser(user.getId(), role);
                    List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                    var authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | NumberFormatException e) {
                log.debug("Rejecting invalid JWT on {}: {}", request.getRequestURI(), e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length());
        }
        return null;
    }
}
