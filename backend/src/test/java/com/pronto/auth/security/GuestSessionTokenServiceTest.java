package com.pronto.auth.security;

import com.pronto.common.security.AuthenticatedUser;
import com.pronto.common.security.UploadOwner;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guest upload session: what it proves, and — mostly — what it cannot be made to prove.
 *
 * <p>This token is the only new credential in the system, so the tests that matter are the ones
 * asserting its boundaries: it is not a JWT, a JWT is not one of these, and a namespace can only be
 * claimed by the holder of the token that named it.
 */
class GuestSessionTokenServiceTest {

    private static final String SECRET =
            "test-only-secret-that-is-comfortably-longer-than-thirty-two-bytes";

    private GuestSessionTokenService service;
    private UploadOwnerResolver resolver;

    @BeforeEach
    void setUp() {
        service = new GuestSessionTokenService(SECRET, 3600);
        resolver = new UploadOwnerResolver(service);
    }

    @Test
    void issuedTokenResolvesBackToAStableWellFormedGuestId() {
        GuestSessionTokenService.GuestSession session = service.issue();

        Optional<String> guestId = service.resolveGuestId(session.token());

        assertThat(guestId).isPresent();
        assertThat(GuestSessionTokenService.isWellFormedGuestId(guestId.get())).isTrue();
        // Stable: the same token always names the same namespace, or a resumed draft's keys would
        // stop being the caller's own between two requests.
        assertThat(service.resolveGuestId(session.token())).isEqualTo(guestId);
        assertThat(session.expiresInSeconds()).isEqualTo(3600);
    }

    @Test
    void twoSessionsGetDifferentNamespaces() {
        assertThat(service.resolveGuestId(service.issue().token()))
                .isNotEqualTo(service.resolveGuestId(service.issue().token()));
    }

    @Test
    void aTokenSignedWithADifferentJwtSecretIsRefused() {
        String foreign = new GuestSessionTokenService("a-completely-different-secret-of-sufficient-length", 3600)
                .issue().token();

        assertThat(service.resolveGuestId(foreign)).isEmpty();
    }

    @Test
    void anExpiredSessionIsRefused() {
        // Negative TTL: issued already expired.
        String expired = new GuestSessionTokenService(SECRET, -60).issue().token();

        assertThat(service.resolveGuestId(expired)).isEmpty();
    }

    @Test
    void garbageAndAbsenceAreRefusedIdentically() {
        assertThat(service.resolveGuestId(null)).isEmpty();
        assertThat(service.resolveGuestId("")).isEmpty();
        assertThat(service.resolveGuestId("not.a.token")).isEmpty();
    }

    // ---- the two directions of confusion between this token and a real JWT ----

    @Test
    void aUserJwtIsNotAcceptedAsAGuestSession() {
        User user = Mockito.mock(User.class);
        when_id(user, 7L);
        String userJwt = new JwtService(SECRET, 3600).generateToken(user);

        // Not "rejected by a claim check" — the key is derived, so it does not even verify.
        assertThat(service.resolveGuestId(userJwt)).isEmpty();
    }

    @Test
    void aGuestSessionIsNotAcceptedAsAUserJwt() {
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        JwtPrincipalResolver principalResolver =
                new JwtPrincipalResolver(new JwtService(SECRET, 3600), userRepository);

        String guestToken = service.issue().token();

        assertThat(principalResolver.resolve(guestToken)).isEmpty();
        Mockito.verifyNoInteractions(userRepository);
    }

    // ---- UploadOwnerResolver ----

    @Test
    void resolverRefusesACallerWhoProvedNothing() {
        assertThatThrownBy(() -> resolver.requireIdentified(null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void resolverTreatsAnUnverifiableGuestHeaderAsAbsentRatherThanAsAnError() {
        // A stale token in a returning visitor's localStorage is ordinary, not an attack -- but it
        // must buy nothing, so an anonymous caller presenting one is still 401.
        assertThatThrownBy(() -> resolver.requireIdentified(null, "stale-or-forged"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        // And an authenticated caller presenting one is simply a customer with no guest namespace.
        UploadOwner owner = resolver.requireIdentified(new AuthenticatedUser(42L, "CUSTOMER"), "stale-or-forged");
        assertThat(owner.customerId()).isEqualTo(42L);
        assertThat(owner.guestId()).isNull();
    }

    @Test
    void resolverCarriesBothIdentitiesWhenBothWereProved() {
        // The auth-transition case: a guest who registered mid-flow.
        String guestToken = service.issue().token();

        UploadOwner owner = resolver.requireIdentified(new AuthenticatedUser(42L, "CUSTOMER"), guestToken);

        assertThat(owner.customerId()).isEqualTo(42L);
        assertThat(owner.guestId()).isEqualTo(service.resolveGuestId(guestToken).orElseThrow());
        // New uploads still land under the account, which is the ownership that outlives the session.
        assertThat(owner.preferredKeyOwnerSegment()).isEqualTo("42");
    }

    private static void when_id(User user, Long id) {
        Mockito.when(user.getId()).thenReturn(id);
        Mockito.when(user.getRole()).thenReturn(UserRole.CUSTOMER);
    }
}
