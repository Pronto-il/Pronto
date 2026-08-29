package com.pronto.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Mints and verifies the <b>guest upload session</b> — the backend-authoritative identity a
 * visitor with no account uses to own the photos they attach to a problem description.
 *
 * <p><b>Why a new token mechanism was necessary, stated because it should not have been.</b>
 * Deferred authentication (the commit immediately preceding this work) gave the product a guest
 * journey, but the only thing that survived it was a {@code localStorage} booking draft with
 * {@code ownerId: null} — pure client state. There was no backend-side guest identifier of any
 * kind: no session, no draft row, no token. Every existing ownership mechanism in the system
 * resolves to a {@code users} row id, and the two things this feature must not do are invent a
 * fake {@code users} row for a visitor and trust a client-supplied identifier. So something had
 * to be minted, and this is the narrowest thing that works: a bearer capability naming a random
 * namespace, granting nothing except the right to write into and read back that namespace.
 *
 * <p><b>What holding one of these lets you do — the whole list.</b> Upload an image under
 * {@code guests/{guestId}/...} through the same endpoint, the same validation and the same limits
 * an authenticated customer uses; re-presign a key you already own in that namespace; and, once
 * you have separately authenticated, promote those keys onto your own account at issue creation.
 * It authenticates nothing else and authorises nothing else: it cannot read another namespace,
 * cannot create an issue, cannot create an order, and is not accepted anywhere the
 * {@code Authorization} header is expected.
 *
 * <p><b>Signed with a key derived from, and unusable as, the JWT secret.</b> The signing key is
 * {@code HMAC-SHA256(pronto.jwt.secret, "pronto.guest-session.v1")}. That gives hard domain
 * separation for free: a guest session token can never verify as a user JWT and a user JWT can
 * never verify as a guest session, so no claim-inspection discipline has to hold for that to stay
 * true. It also means no new secret to distribute — deliberate, after
 * {@code STORAGE_LOCAL_HMAC_SECRET} showed what adding one costs a deployment recipe. The JWT
 * secret is already guarded in every environment by {@link JwtSecretStartupGuard}, so this key
 * inherits that strength check rather than needing its own.
 *
 * <p><b>Expiry.</b> {@code pronto.auth.guest-session-ttl-seconds}, default 24h — the same figure
 * as {@code pronto.jwt.expiration-seconds}, and for the same reason: it comfortably outlives one
 * booking journey (including a trip through registration and its OTP) without leaving a namespace
 * writable indefinitely. There is no revocation list; a guest session is not an account, holds no
 * personal data, and expiring is the only end it needs.
 */
@Component
public class GuestSessionTokenService {

    /**
     * The header a guest session travels in. Deliberately <em>not</em> {@code Authorization}: this
     * is not an identity, the two are independently present (a registered customer mid-flow sends
     * both), and reusing that header would put a non-JWT into the one place
     * {@link JwtAuthenticationFilter} looks.
     */
    public static final String HEADER = "X-Pronto-Guest-Session";

    /** Domain-separation label. Changing it invalidates every outstanding guest session. */
    private static final String KEY_DERIVATION_LABEL = "pronto.guest-session.v1";

    private static final String CLAIM_TYPE = "typ";
    private static final String TOKEN_TYPE = "GUEST_UPLOAD_SESSION";

    /**
     * A guest id is always a {@link UUID#randomUUID()} this class generated. Re-checking the shape
     * on the way back in is not redundant with the signature: it is what guarantees a guest id can
     * never contain {@code /} or {@code ..} and therefore can never be spliced into a storage key
     * to reach outside its own namespace, no matter what a future call site does with it.
     */
    private static final Pattern GUEST_ID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** What {@code POST /api/storage/guest-sessions} hands back. */
    public record GuestSession(String token, long expiresInSeconds) {
    }

    private final SecretKey key;
    private final long ttlSeconds;

    public GuestSessionTokenService(
            @Value("${pronto.jwt.secret}") String jwtSecret,
            @Value("${pronto.auth.guest-session-ttl-seconds:86400}") long ttlSeconds) {
        this.key = deriveKey(jwtSecret);
        this.ttlSeconds = ttlSeconds;
    }

    public GuestSession issue() {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim(CLAIM_TYPE, TOKEN_TYPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
        return new GuestSession(token, ttlSeconds);
    }

    /**
     * The guest id this token proves ownership of, or empty for every rejection reason — missing,
     * malformed, expired, wrong signature, wrong token type, or a subject that is not a UUID.
     * Returning empty rather than throwing mirrors {@link JwtPrincipalResolver#resolve}: the caller
     * decides what "no guest session" means for its route, and for most routes it simply means the
     * caller is not a guest.
     */
    public Optional<String> resolveGuestId(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!TOKEN_TYPE.equals(claims.get(CLAIM_TYPE, String.class))) {
                return Optional.empty();
            }
            String guestId = claims.getSubject();
            return guestId != null && GUEST_ID_PATTERN.matcher(guestId).matches()
                    ? Optional.of(guestId)
                    : Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** True iff {@code candidate} has the exact shape this class mints — see {@link #GUEST_ID_PATTERN}. */
    public static boolean isWellFormedGuestId(String candidate) {
        return candidate != null && GUEST_ID_PATTERN.matcher(candidate).matches();
    }

    private static SecretKey deriveKey(String jwtSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Keys.hmacShaKeyFor(mac.doFinal(KEY_DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to derive the guest-session signing key.", e);
        }
    }
}
