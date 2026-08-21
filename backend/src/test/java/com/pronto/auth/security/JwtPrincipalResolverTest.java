package com.pronto.auth.security;

import com.pronto.common.security.AuthenticatedUser;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The shared token-to-identity decision, now used by both the HTTP filter and the STOMP
 * interceptor. Its rejection rules are tested here once, so both transports inherit the same
 * behaviour rather than each growing its own subtly different version.
 */
class JwtPrincipalResolverTest {

    private static final String TOKEN = "a.b.c";
    private static final Long USER_ID = 7L;

    private JwtService jwtService;
    private UserRepository userRepository;
    private JwtPrincipalResolver resolver;

    @BeforeEach
    void setUp() {
        jwtService = Mockito.mock(JwtService.class);
        userRepository = Mockito.mock(UserRepository.class);
        resolver = new JwtPrincipalResolver(jwtService, userRepository);
    }

    private void stubValidToken() {
        Claims claims = Mockito.mock(Claims.class);
        when(claims.getSubject()).thenReturn(String.valueOf(USER_ID));
        when(claims.get("role", String.class)).thenReturn("CUSTOMER");
        when(jwtService.parseClaims(TOKEN)).thenReturn(claims);
    }

    private static User user(Instant deletedAt) {
        User user = new User("Test User", "a@b.com", "hash", UserRole.CUSTOMER);
        setField(user, "id", USER_ID);
        setField(user, "deletedAt", deletedAt);
        return user;
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            var field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolvesAValidTokenToThePrincipal() {
        stubValidToken();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(null)));

        Optional<AuthenticatedUser> principal = resolver.resolve(TOKEN);

        assertThat(principal).contains(new AuthenticatedUser(USER_ID, "CUSTOMER"));
    }

    /**
     * The revocation rule from api-contract.md §3.1 — a still-valid signature over a deleted user
     * is not an identity. Living here means WebSocket sessions inherit it too.
     */
    @Test
    void aValidSignatureOverASoftDeletedUserIsRejected() {
        stubValidToken();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(Instant.now())));

        assertThat(resolver.resolve(TOKEN)).isEmpty();
    }

    @Test
    void anUnknownUserIsRejected() {
        stubValidToken();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(TOKEN)).isEmpty();
    }

    @Test
    void aMalformedOrExpiredTokenIsRejected() {
        when(jwtService.parseClaims(anyString())).thenThrow(new JwtException("bad"));

        assertThat(resolver.resolve("nonsense")).isEmpty();
    }

    @Test
    void aNonNumericSubjectIsRejected() {
        Claims claims = Mockito.mock(Claims.class);
        when(claims.getSubject()).thenReturn("not-a-number");
        when(jwtService.parseClaims(TOKEN)).thenReturn(claims);

        assertThat(resolver.resolve(TOKEN)).isEmpty();
    }

    @Test
    void nullAndBlankTokensAreRejectedWithoutParsing() {
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("  ")).isEmpty();
        Mockito.verify(jwtService, Mockito.never()).parseClaims(anyString());
    }

    @Test
    void stripBearerUnwrapsOnlyTheBearerScheme() {
        assertThat(JwtPrincipalResolver.stripBearer("Bearer abc")).isEqualTo("abc");
        assertThat(JwtPrincipalResolver.stripBearer("Basic abc")).isNull();
        assertThat(JwtPrincipalResolver.stripBearer("abc")).isNull();
        assertThat(JwtPrincipalResolver.stripBearer(null)).isNull();
    }
}
