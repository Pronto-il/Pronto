package com.pronto.users.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The backend half of MS1's legacy-account policy: an account with an unproved phone number
 * authenticates normally but cannot perform the operations that end with a stranger arriving at
 * somebody's home.
 */
class ContactVerificationGuardTest {

    private static final Long USER_ID = 42L;

    private UserRepository userRepository;
    private ContactVerificationGuard guard;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        guard = new ContactVerificationGuard(userRepository);
    }

    private User account(boolean emailVerified, boolean phoneVerified) {
        User user = new User("Israel Israeli", "customer@example.com", "hash", UserRole.CUSTOMER);
        try {
            Field id = User.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(user, USER_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        user.setEmailVerified(emailVerified);
        user.setPhoneVerified(phoneVerified);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void bothChannelsVerified_passes() {
        account(true, true);

        assertThatCode(() -> guard.requireVerifiedContactChannels(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    void anUnverifiedPhone_isRefusedWithItsOwnActionableCode() {
        account(true, false);

        assertThatThrownBy(() -> guard.requireVerifiedContactChannels(USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        // Not a generic FORBIDDEN: the caller MAY do this, as soon as they finish a
                        // step that takes thirty seconds, and the client needs to be able to tell
                        // those apart to route them there instead of showing a dead end.
                        .isEqualTo(ErrorCode.PHONE_VERIFICATION_REQUIRED));
    }

    @Test
    void anUnverifiedEmail_isAlsoRefused() {
        account(false, true);

        assertThatThrownBy(() -> guard.requireVerifiedContactChannels(USER_ID))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aLegacyAccountWithNoPhoneAtAll_isRefused() {
        User user = account(true, false);
        user.setPhone(null);

        assertThatThrownBy(() -> guard.requireVerifiedContactChannels(USER_ID))
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.PHONE_VERIFICATION_REQUIRED));
    }

    @Test
    void aSoftDeletedAccountIsUnauthorized_notMerelyUnverified() {
        User user = account(true, true);
        user.setDeletedAt(Instant.now());

        assertThatThrownBy(() -> guard.requireVerifiedContactChannels(USER_ID))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void aVanishedAccountIsUnauthorized() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireVerifiedContactChannels(USER_ID))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void isFullyVerified_isTheSingleQuestionTheGateAsks() {
        assertThat(account(true, true).isFullyVerified()).isTrue();
        assertThat(account(true, false).isFullyVerified()).isFalse();
        assertThat(account(false, true).isFullyVerified()).isFalse();
        assertThat(account(false, false).isFullyVerified()).isFalse();
    }
}
