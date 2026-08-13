package com.pronto.auth.security;

import com.pronto.users.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT issuance/parsing per {@code docs/architecture/api-contract.md} §3.1: HS256, claims
 * {@code sub} (user id), {@code role}, {@code iat}, {@code exp}; 24h expiry by default.
 * Secret is sourced from {@code pronto.jwt.secret} (env-var-backed in
 * {@code application.yml}; the checked-in default is local-dev-only and explicitly not
 * production-safe — see that file's comment).
 */
@Component
public class JwtService {

    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtService(
            @Value("${pronto.jwt.secret}") String secret,
            @Value("${pronto.jwt.expiration-seconds:86400}") long expirationSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationSeconds);
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    /**
     * @throws JwtException if the token is malformed, expired, or has an invalid signature.
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
